package com.papyrus.app.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.papyrus.app.crash.CrashReportStore
import com.papyrus.app.viewer.NormRect
import com.papyrus.app.viewer.OfficeEncryption
import com.papyrus.app.viewer.OutlineEntry
import com.papyrus.app.viewer.PdfEncryption
import com.papyrus.app.viewer.TextSniffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

class DocumentRepository(
    private val context: Context,
    private val dao: DocumentDao,
    private val progressDao: ReadingProgressDao,
    private val bookmarkDao: BookmarkDao,
    private val highlightDao: HighlightDao,
    private val pdfOutlineDao: PdfOutlineDao,
    private val searchDao: SearchDao,
    private val settings: SettingsRepository,
) {
    private val resolver get() = context.contentResolver

    /** In-memory only, and several at once: a second external file opens beside the first. */
    private val handedOver = HandedOverStore<DocumentEntity>()
    private val crashReports = mutableMapOf<Long, DocumentEntity>()
    private var nextCrashReportId = -1000000L

    // Remembers the encryption verdict so the next viewer skips the whole-file check; read once, then forgotten.
    private val knownPdfEncryption = ConcurrentHashMap<String, PdfEncryption>()

    val documents: Flow<List<DocumentEntity>> = dao.observeAll()

    suspend fun restoreRequest(id: Long, format: DocumentFormat): RestoreRequest? {
        if (id <= 0 || !settings.current.rememberPosition) return null
        return withContext(Dispatchers.IO) { progressDao.get(id)?.toRestoreRequest(format) }
    }

    // Writes only that table, so Home's order (lastOpenedAt) is untouched.
    suspend fun saveReadingProgress(id: Long, sample: PositionSample) {
        if (id <= 0 || !settings.current.rememberPosition) return
        withContext(Dispatchers.IO) {
            progressDao.save(
                documentId = id,
                locationKind = sample.location.kind.code,
                locationUnit = sample.location.unit,
                locationFraction = sample.location.fraction,
                unitCount = sample.unitCount,
                zoomScale = sample.zoom,
                progress = sample.progress,
                updatedAt = System.currentTimeMillis(),
            )
        }
    }

    fun observeBookmarks(id: Long): Flow<List<BookmarkEntity>> =
        if (id <= 0) flowOf(emptyList()) else bookmarkDao.observe(id)

    suspend fun addBookmark(id: Long, location: ReadingLocation, label: String): BookmarkAdd {
        if (id <= 0) return BookmarkAdd.NotInLibrary
        return withContext(Dispatchers.IO) { bookmarkDao.addIfAllowed(id, location, label, System.currentTimeMillis(), MAX_BOOKMARKS_PER_DOCUMENT) }
    }

    suspend fun renameBookmark(id: Long, label: String) = withContext(Dispatchers.IO) { bookmarkDao.rename(id, label) }

    suspend fun deleteBookmark(id: Long) = withContext(Dispatchers.IO) { bookmarkDao.delete(id) }

    suspend fun restoreBookmark(bookmark: BookmarkEntity) = withContext(Dispatchers.IO) {
        bookmarkDao.restore(
            id = bookmark.id,
            documentId = bookmark.documentId,
            locationKind = bookmark.locationKind,
            locationUnit = bookmark.locationUnit,
            locationFraction = bookmark.locationFraction,
            label = bookmark.label,
            createdAt = bookmark.createdAt,
        )
    }

    fun observeHighlights(id: Long): Flow<List<HighlightEntity>> =
        if (id <= 0) flowOf(emptyList()) else highlightDao.observe(id)

    suspend fun addHighlights(id: Long, boxesByPage: Map<Int, List<NormRect>>) {
        if (id <= 0 || boxesByPage.isEmpty()) return
        val rows = boxesByPage.map { (page, boxes) -> HighlightEntity(documentId = id, page = page, rects = encodeRects(boxes)) }
        withContext(Dispatchers.IO) { highlightDao.insert(rows) }
    }

    suspend fun deleteHighlights(ids: List<Long>) = withContext(Dispatchers.IO) {
        // Older SQLite builds cap a statement at 999 variables, so a very large removal goes in slices.
        ids.chunked(MAX_IDS_PER_STATEMENT).forEach { highlightDao.delete(it) }
    }

    suspend fun setHighlightNote(id: Long, note: String) = withContext(Dispatchers.IO) {
        val cleaned = cleanHighlightNote(note)
        highlightDao.setNote(id, cleaned, (if (cleaned == null) HighlightKind.HIGHLIGHT else HighlightKind.NOTE).code)
    }

    // Reused only while the file is the same size and the encoding version matches.
    suspend fun cachedOutline(document: DocumentEntity): List<OutlineEntry>? = withContext(Dispatchers.IO) {
        if (document.id <= 0) return@withContext null
        val row = pdfOutlineDao.get(document.id) ?: return@withContext null
        if (row.sourceSize != document.sizeBytes || row.cacheVersion != PDF_OUTLINE_CACHE_VERSION) {
            return@withContext null
        }
        decodeOutline(row.entries)
    }

    // Never store an outline read from an unlocked copy: its titles come from a password-protected file.
    suspend fun storeOutline(document: DocumentEntity, entries: List<OutlineEntry>) = withContext(Dispatchers.IO) {
        if (document.id <= 0) return@withContext
        pdfOutlineDao.put(
            PdfOutlineEntity(
                documentId = document.id,
                sourceSize = document.sizeBytes,
                cacheVersion = PDF_OUTLINE_CACHE_VERSION,
                entries = encodeOutline(entries),
            ),
        )
    }

    // Read aloud needs this for a PDF, whose pages are read one at a time so the viewer cannot tell if there is text.
    fun observeTextSummary(document: DocumentEntity): Flow<TextSummaryRow?> =
        if (document.id <= 0) {
            flowOf(null)
        } else {
            searchDao.observeTextSummary(document.id)
                .map { rows -> rows.firstOrNull()?.takeIf { it.sourceSize == document.sizeBytes } }
        }

    private fun handOver(entity: DocumentEntity): Long = handedOver.add { id -> entity.copy(id = id) }

    fun openCrashReport(file: File): Long {
        val id = nextCrashReportId--
        val entity = DocumentEntity(
            id = id,
            uri = Uri.fromFile(file).toString(),
            title = file.name,
            format = DocumentFormat.CODE,
            sizeBytes = file.length(),
            createdAt = file.lastModified(),
            lastOpenedAt = System.currentTimeMillis(),
        )
        crashReports[id] = entity
        return id
    }

    suspend fun getById(id: Long): DocumentEntity? =
        crashReports[id] ?: if (id < 0) handedOver[id] else dao.getById(id)
    suspend fun markOpened(id: Long) = dao.touch(id, System.currentTimeMillis())
    suspend fun updateFormat(id: Long, format: DocumentFormat) = dao.updateFormat(id, format)

    suspend fun setStarred(ids: List<Long>, starred: Boolean) {
        val library = ids.filter { it > 0 }
        if (library.isEmpty()) return
        // Older SQLite builds cap a statement at 999 variables, so a very large selection goes in slices.
        withContext(Dispatchers.IO) { library.chunked(MAX_IDS_PER_STATEMENT).forEach { dao.setStarred(it, starred) } }
    }

    suspend fun register(uri: Uri): Long = withContext(Dispatchers.IO) {
        val entity = buildEntity(uri)
        // Not indexed by default: a row would put it in the library and persist a grant past the open.
        // With autosave encrypted on, it is indexed like any file, and the password is asked again on open.
        if (entity.format in ENCRYPTABLE && !settings.current.autosaveEncrypted && isEncrypted(entity.format, uri)) {
            return@withContext handOver(entity)
        }
        if (!SafStorage.persistPermission(resolver, uri)) {
            // Grant could not be persisted (provider refused, OS cap reached). Hand over instead of
            // indexing a row that depends on a transient grant which will lapse on reboot.
            return@withContext handOver(entity)
        }
        // buildEntity stamps lastOpenedAt, and upsertByUri carries it onto an existing row too.
        dao.upsertByUri(entity)
    }

    // The intent's read grant lasts only for this entry.
    suspend fun openHandedOver(uri: Uri): Long = withContext(Dispatchers.IO) {
        handOver(buildEntity(uri))
    }

    // A failed insert deletes the copy again. An encrypted PDF is copied as-is; the password is never read or kept.
    suspend fun saveToLibrary(source: DocumentEntity): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // Autosave re-saves the same file on each open, so the duplicate check runs before copying.
        findSavedDuplicate(source)?.let { existing ->
            dao.touch(existing.id, now)
            return@withContext existing.id
        }
        val file = LibraryImport.copyToImports(context, source.uri.toUri())
        // Re-points existing row at the copied file so ID, star, bookmarks and progress survive lapsed grants.
        val indexedOnSource = dao.getByUri(source.uri)
        val row = source.copy(
            id = 0,
            uri = file.toUri().toString(),
            sizeBytes = file.length(),
            createdAt = now,
            lastOpenedAt = now,
        )
        var indexed = false
        try {
            if (indexedOnSource != null) {
                dao.update(
                    indexedOnSource.copy(
                        uri = row.uri,
                        title = row.title,
                        format = row.format,
                        sizeBytes = row.sizeBytes,
                        lastOpenedAt = now,
                    ),
                )
                SafStorage.releasePermission(resolver, source.uri.toUri())
                indexed = true
                indexedOnSource.id
            } else {
                dao.upsertByUri(row).also { indexed = true }
            }
        } finally {
            if (!indexed) file.delete()
        }
    }

    // A same-title row whose app-owned copy holds the same bytes; a row only on [source]'s own URI is no match, since it would not outlive the grant.
    private suspend fun findSavedDuplicate(source: DocumentEntity): DocumentEntity? {
        val sourceUri = source.uri.toUri()
        for (row in dao.getByTitle(source.title)) {
            val candidate = ownedCopy(row.uri) ?: continue
            if (LibraryImport.sameContent(candidate, source.sizeBytes) { openStream(sourceUri) }) return row
        }
        return null
    }

    private fun ownedCopy(uri: String): File? {
        val parsed = uri.toUri()
        if (parsed.scheme != "file") return null
        val path = parsed.path ?: return null
        val file = File(path)
        if (!file.isFile) return null
        return file.takeIf { LibraryImport.isInside(LibraryImport.importsDir(context), it) }
    }

    /** Removes the index entry only, never the user's file; an app-owned copy goes with its row. */
    suspend fun remove(document: DocumentEntity) = withContext(Dispatchers.IO) {
        SafStorage.releasePermission(resolver, document.uri.toUri())
        // Explicit, so the FTS delete trigger fires; the cascade is only a backstop for rows removed
        // by some other path.
        searchDao.deleteText(document.id)
        dao.deleteById(document.id)
        LibraryImport.deleteOwnedCopy(context, document.uri.toUri())
    }

    // [remove] drops the row before the file, so a kill between leaves an orphan. Files at or after [startedAt] are spared: a copy being saved has no row yet.
    suspend fun sweepOrphanedImports(startedAt: Long): Int = withContext(Dispatchers.IO) {
        val referenced = dao.allUris().mapNotNullTo(HashSet()) { ownedCopy(it) }
        LibraryImport.sweepOrphans(LibraryImport.importsDir(context), referenced, startedAt)
    }

    // SAF URIs share as-is. A local copy has no grant and cannot leave as a bare file:// URI, so it
    // goes through a FileProvider.
    suspend fun resolveShareUri(uri: Uri): Uri = withContext(Dispatchers.IO) {
        if (uri.scheme != "file") return@withContext uri
        val path = uri.path ?: return@withContext uri
        val file = File(path)
        val isImport = LibraryImport.isInside(LibraryImport.importsDir(context), file)
        val isCrashReport = CrashReportStore.isInside(context, file)
        if (!isImport && !isCrashReport) return@withContext uri
        FileProvider.getUriForFile(context, "${context.packageName}$FILE_PROVIDER_SUFFIX", file)
    }

    suspend fun pdfEncryption(uri: Uri): PdfEncryption = withContext(Dispatchers.IO) {
        knownPdfEncryption.remove(uri.toString()) ?: PdfEncryption.status { openStream(uri) }
    }

    suspend fun probeShare(document: DocumentEntity): SafAccess =
        withContext(Dispatchers.IO) { SafStorage.probeAccess(resolver, document.uri.toUri()) }

    suspend fun mimeTypeOf(document: DocumentEntity): String? =
        withContext(Dispatchers.IO) { SafStorage.queryMimeType(resolver, document.uri.toUri()) }

    suspend fun shareTypeFor(document: DocumentEntity): String {
        val reported = mimeTypeOf(document)
            ?: if (document.title.endsWith(".json", ignoreCase = true)) "application/json" else null
        return shareMimeType(document.format, reported)
    }

    // A SAF grant can only come from the user picking the file again, so the re-pick must match
    // [matchesRepick]: same name and format.
    suspend fun regrant(expected: DocumentEntity, picked: Uri): Boolean = withContext(Dispatchers.IO) {
        val meta = SafStorage.queryMetadata(context, picked)
        val pickedFormat = resolveFormat(picked, meta.displayName, meta.mimeType)
        if (!matchesRepick(expected.title, expected.format, meta.displayName, pickedFormat)) return@withContext false
        if (!SafStorage.persistPermission(resolver, picked)) return@withContext false
        // A handed-over document has no row to re-point, so the in-memory entry carries the new URI.
        if (expected.id < 0) {
            handedOver[expected.id] = expected.copy(uri = picked.toString())
            return@withContext true
        }
        dao.updateUri(expected.id, picked.toString())
        // The row no longer names the old URI, so its grant would sit dead in the OS's capped pool and push
        // live ones out at the cap. A re-pick that returns the same URI is the grant just taken: that stays.
        if (expected.uri != picked.toString()) SafStorage.releasePermission(resolver, expected.uri.toUri())
        true
    }

    /** An unreadable file reads as not encrypted, so the renderer fails rather than prompting. */
    private fun isEncrypted(format: DocumentFormat, uri: Uri): Boolean =
        when (format) {
            DocumentFormat.PDF -> PdfEncryption.status { openStream(uri) }.also { status ->
                if (status != PdfEncryption.NOT_ENCRYPTED) {
                    if (knownPdfEncryption.size >= MAX_KNOWN_PDF_STATUS) knownPdfEncryption.clear()
                    knownPdfEncryption[uri.toString()] = status
                }
            } != PdfEncryption.NOT_ENCRYPTED
            DocumentFormat.DOCX, DocumentFormat.ODT, DocumentFormat.DOC -> OfficeEncryption.isEncrypted({ openStream(uri) })
            else -> false
        }

    private fun openStream(uri: Uri): InputStream =
        resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())

    private suspend fun buildEntity(uri: Uri): DocumentEntity {
        val meta = SafStorage.queryMetadata(context, uri)
        val now = System.currentTimeMillis()
        return DocumentEntity(
            uri = uri.toString(),
            title = meta.displayName.ifBlank { UNTITLED },
            format = resolveFormat(uri, meta.displayName, meta.mimeType),
            sizeBytes = meta.sizeBytes,
            createdAt = now,
            lastOpenedAt = now,
        )
    }

    private fun resolveFormat(uri: Uri, displayName: String, mimeType: String?): DocumentFormat {
        val detected = DocumentFormat.detect(displayName, mimeType)
        if (detected != DocumentFormat.UNKNOWN) return detected
        val sniffed = runCatching {
            resolver.openInputStream(uri)?.use(TextSniffer::looksLikeText) ?: false
        }.getOrDefault(false)
        return if (sniffed) DocumentFormat.CODE else DocumentFormat.UNKNOWN
    }

    private companion object {
        const val FILE_PROVIDER_SUFFIX = ".fileprovider"
        const val MAX_KNOWN_PDF_STATUS = 8
        const val MAX_IDS_PER_STATEMENT = 900

        val ENCRYPTABLE = setOf(
            DocumentFormat.PDF,
            DocumentFormat.DOCX,
            DocumentFormat.ODT,
            DocumentFormat.DOC,
        )
    }
}
