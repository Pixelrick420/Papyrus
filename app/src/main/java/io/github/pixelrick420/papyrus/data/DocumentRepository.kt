package io.github.pixelrick420.papyrus.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import io.github.pixelrick420.papyrus.crash.CrashReportStore
import io.github.pixelrick420.papyrus.viewer.NormRect
import io.github.pixelrick420.papyrus.viewer.OfficeEncryption
import io.github.pixelrick420.papyrus.viewer.OutlineEntry
import io.github.pixelrick420.papyrus.viewer.localSourceFile
import io.github.pixelrick420.papyrus.viewer.PdfEncryption
import io.github.pixelrick420.papyrus.viewer.PdfEncryptionVerdictKey
import io.github.pixelrick420.papyrus.viewer.PdfEncryptionVerdicts
import io.github.pixelrick420.papyrus.viewer.TextSniffer
import io.github.pixelrick420.papyrus.viewer.textCacheMetadata
import io.github.pixelrick420.papyrus.viewer.textCacheModified
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

class DocumentRepository(
    private val context: Context,
    private val dao: DocumentDao,
    private val progressDao: ReadingProgressDao,
    private val bookmarkDao: BookmarkDao,
    private val highlightDao: HighlightDao,
    private val pdfOutlineDao: PdfOutlineDao,
    private val searchDao: SearchDao,
    private val settings: SettingsRepository,
    encryptionCheck: ((DocumentFormat, Uri, Long) -> Boolean)? = null,
    persistGrant: ((Uri) -> Boolean)? = null,
    releaseGrant: ((Uri) -> Unit)? = null,
) {
    private val resolver get() = context.contentResolver

    private val isEncryptedFor: (DocumentFormat, Uri, Long) -> Boolean =
        encryptionCheck ?: { format, uri, size -> isEncryptedSafely(format, uri, size) }
    private val persistFor: (Uri) -> Boolean =
        persistGrant ?: { uri: Uri -> SafStorage.persistPermission(resolver, uri) }
    private val releaseFor: (Uri) -> Unit =
        releaseGrant ?: { uri: Uri -> SafStorage.releasePermission(resolver, uri) }

    /** In-memory only, and several at once: a second external file opens beside the first. */
    private val handedOver = HandedOverStore<DocumentEntity>()
    private val crashReports = HandedOverStore<DocumentEntity>(firstId = CRASH_REPORT_FIRST_ID)
    private val duplicateFinder = SavedDuplicateFinder(
        rowsByTitle = { title -> dao.getByTitle(title) },
        ownedCopy = { uri -> ownedCopy(uri) },
        openSource = { source -> openStream(source.uri.toUri()) },
    )

    // A verdict belongs to one version of a source; size and modified time invalidate it when that source changes.
    private val pdfVerdicts = PdfEncryptionVerdicts(MAX_KNOWN_PDF_STATUS) { key ->
        PdfEncryption.status { openStream(key.uri.toUri()) }
    }

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

    // Reused only while size, edit time and version match; 0 marks a legacy row.
    suspend fun cachedOutline(document: DocumentEntity): List<OutlineEntry>? = withContext(Dispatchers.IO) {
        if (document.id <= 0) return@withContext null
        val row = pdfOutlineDao.get(document.id) ?: return@withContext null
        val modified = sourceModified(document)
        if ((document.sizeBytes > 0 && row.sourceSize != document.sizeBytes) || row.cacheVersion != PDF_OUTLINE_CACHE_VERSION ||
            row.sourceModified == 0L || row.sourceModified != modified
        ) {
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
                sourceModified = sourceModified(document),
                cacheVersion = PDF_OUTLINE_CACHE_VERSION,
                entries = encodeOutline(entries),
            ),
        )
    }

    // Content providers report the edit time; an app-owned copy uses its file stamp.
    private fun sourceModified(document: DocumentEntity): Long {
        val uri = document.uri.toUri()
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: return 0
            return File(path).takeIf { it.isFile }?.lastModified()?.takeIf { it > 0 } ?: 0
        }
        return textCacheModified(context, uri) ?: 0
    }

    // Read aloud needs this for a PDF, whose pages are read one at a time so the viewer cannot tell if there is text.
    fun observeTextSummary(document: DocumentEntity): Flow<TextSummaryRow?> =
        if (document.id <= 0) {
            flowOf(null)
        } else {
            searchDao.observeTextSummary(document.id)
                .map { rows -> rows.firstOrNull()?.takeIf { document.sizeBytes <= 0 || it.sourceSize == document.sizeBytes } }
        }

    private fun handOver(entity: DocumentEntity): Long = handedOver.add { id -> entity.copy(id = id) }

    fun openCrashReport(file: File): Long {
        val entity = DocumentEntity(
            id = 0,
            uri = Uri.fromFile(file).toString(),
            title = file.name,
            format = DocumentFormat.CODE,
            sizeBytes = file.length(),
            createdAt = file.lastModified(),
            lastOpenedAt = System.currentTimeMillis(),
        )
        return crashReports.add { id -> entity.copy(id = id) }
    }

    suspend fun getById(id: Long): DocumentEntity? =
        crashReports[id] ?: if (id < 0) handedOver[id] else dao.getById(id)

    // A provider can replace a file behind the same URI: refresh the size the cache comparisons rely on.
    suspend fun markOpened(id: Long) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val row = dao.getById(id)
        val size = if (row == null) 0L else SafStorage.queryMetadata(context, row.uri.toUri()).sizeBytes
        if (size > 0 && size != row?.sizeBytes) dao.touchWithSize(id, now, size) else dao.touch(id, now)
    }

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
        if (entity.format in ENCRYPTABLE && !settings.current.autosaveEncrypted && isEncryptedFor(entity.format, uri, entity.sizeBytes)) {
            return@withContext handOver(entity)
        }
        if (!persistFor(uri)) {
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
                completeIndexedRepoint(
                    markIndexed = { indexed = true },
                    release = { releaseFor(source.uri.toUri()) },
                )
                indexedOnSource.id
            } else {
                dao.upsertByUri(row).also { indexed = true }
            }
        } finally {
            if (!indexed) file.delete()
        }
    }

    // A same-title row whose app-owned copy holds the same bytes; a row only on [source]'s own URI is no match, since it would not outlive the grant.
    private suspend fun findSavedDuplicate(source: DocumentEntity): DocumentEntity? = duplicateFinder.find(source)

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
        // The row goes first: a revoked grant with a live row would leave the file unreadable.
        dao.deleteDocument(document.id)
        SafStorage.releasePermission(resolver, document.uri.toUri())
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
        pdfVerdicts.of(pdfVerdictKey(uri))
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
    // The new grant must be released even when a cancelled rebind is rethrown.
    @Suppress("TooGenericExceptionCaught", "SuspendFunSwallowedCancellation")
    suspend fun regrant(expected: DocumentEntity, picked: Uri): Boolean = withContext(Dispatchers.IO) {
        val meta = SafStorage.queryMetadata(context, picked)
        val pickedFormat = resolveFormat(picked, meta.displayName, meta.mimeType)
        if (!matchesRepick(expected.title, expected.format, meta.displayName, pickedFormat)) return@withContext false
        val pickedUri = picked.toString()
        // Another row already owns this URI, so the grant would be taken for nothing.
        val owner = dao.getByUri(pickedUri)
        if (owner != null && owner.id != expected.id) return@withContext false
        // A handed-over document has no row to re-point; its re-pick already carries a transient read grant.
        if (expected.id < 0) {
            if (expected.uri != pickedUri) SafStorage.releasePermission(resolver, expected.uri.toUri())
            handedOver[expected.id] = expected.copy(uri = pickedUri)
            return@withContext true
        }
        if (!SafStorage.persistPermission(resolver, picked)) return@withContext false
        val rebound = try {
            dao.rebindUri(id = expected.id, uri = pickedUri)
        } catch (e: Exception) {
            SafStorage.releasePermission(resolver, picked)
            throw e
        }
        if (!rebound) {
            SafStorage.releasePermission(resolver, picked)
            return@withContext false
        }
        if (expected.uri != pickedUri) SafStorage.releasePermission(resolver, expected.uri.toUri())
        true
    }

    private fun isEncryptedSafely(format: DocumentFormat, uri: Uri, sizeBytes: Long): Boolean = try {
        isEncrypted(format, uri, sizeBytes)
    } catch (_: OutOfMemoryError) {
        false
    }

    private fun pdfVerdictKey(uri: Uri): PdfEncryptionVerdictKey {
        val id = uri.toString()
        val file = localSourceFile(id)
        if (file != null) return PdfEncryptionVerdictKey(id, file.length(), file.lastModified())
        val metadata = textCacheMetadata(context, uri)
        return PdfEncryptionVerdictKey(id, metadata?.size ?: 0L, metadata?.modified ?: 0L)
    }

    /** A damaged file reads as not encrypted, as in PDF, so rendering fails generically instead of prompting. */
    private fun isEncrypted(format: DocumentFormat, uri: Uri, sizeBytes: Long): Boolean =
        when (format) {
            DocumentFormat.PDF -> pdfVerdicts.of(pdfVerdictKey(uri)) != PdfEncryption.NOT_ENCRYPTED
            DocumentFormat.DOCX, DocumentFormat.PPTX, DocumentFormat.ODT, DocumentFormat.DOC ->
                OfficeEncryption.isEncrypted({ openStream(uri) }, knownLength = sizeBytes, sourceFile = localSourceFile(uri.toString()))
            else -> false
        }

    private fun openStream(uri: Uri): InputStream = resolver.openRequired(uri)

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

    companion object {
        const val FILE_PROVIDER_SUFFIX = ".fileprovider"
        const val CRASH_REPORT_FIRST_ID = -1000000L
        const val MAX_KNOWN_PDF_STATUS = 8
        const val MAX_IDS_PER_STATEMENT = 900

        val ENCRYPTABLE = setOf(
            DocumentFormat.PDF,
            DocumentFormat.DOCX,
            DocumentFormat.PPTX,
            DocumentFormat.ODT,
            DocumentFormat.DOC,
        )
    }
}

internal fun completeIndexedRepoint(
    markIndexed: () -> Unit,
    release: () -> Unit,
) {
    markIndexed()
    release()
}
