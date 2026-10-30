package com.papyrus.app.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.papyrus.app.viewer.NormRect
import com.papyrus.app.viewer.OfficeEncryption
import com.papyrus.app.viewer.OutlineEntry
import com.papyrus.app.viewer.PdfEncryption
import com.papyrus.app.viewer.TextSniffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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

    /** In-memory only; ids are negative (Room's positive), -1 the viewer's sentinel, so counting starts at -2. */
    private val handedOver = mutableMapOf<Long, DocumentEntity>()
    private var nextHandedOverId = -2L

    /**
     * Encryption verdict [register] reached for a PDF it handed over, so the next viewer skips the
     * whole-file check. Read once, then forgotten; cleared past a handful of entries.
     */
    private val knownPdfEncryption = ConcurrentHashMap<String, PdfEncryption>()

    val documents: Flow<List<DocumentEntity>> = dao.observeAll()

    /**
     * The stored position of library document [id] as a restore request, or `null`: no row, a transient
     * document, the setting off, or a row that no longer fits the document's [format].
     */
    suspend fun restoreRequest(id: Long, format: DocumentFormat): RestoreRequest? {
        if (id <= 0 || !settings.current.rememberPosition) return null
        return withContext(Dispatchers.IO) { progressDao.get(id)?.toRestoreRequest(format) }
    }

    /**
     * Stores [sample] as the position of library document [id]. Writes only that table, so Home's order
     * (`lastOpenedAt`) is untouched, and does nothing for a transient id or with the setting off.
     */
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

    /**
     * The bookmarks of library document [id], live; nothing for a transient document, which has no
     * row to hang them from. Scoped to the one document, so another document's changes never re-emit it.
     */
    fun observeBookmarks(id: Long): Flow<List<BookmarkEntity>> =
        if (id <= 0) flowOf(emptyList()) else bookmarkDao.observe(id)

    /** Adds a bookmark at [location]; see [BookmarkDao.addIfAllowed] for when it refuses. */
    suspend fun addBookmark(id: Long, location: ReadingLocation, label: String): BookmarkAdd {
        if (id <= 0) return BookmarkAdd.NotInLibrary
        return withContext(Dispatchers.IO) { bookmarkDao.addIfAllowed(id, location, label, System.currentTimeMillis(), MAX_BOOKMARKS_PER_DOCUMENT) }
    }

    suspend fun renameBookmark(id: Long, label: String) = withContext(Dispatchers.IO) { bookmarkDao.rename(id, label) }

    suspend fun deleteBookmark(id: Long) = withContext(Dispatchers.IO) { bookmarkDao.delete(id) }

    /** Undo for a delete: the same row, id and creation time included, so the list returns to what it was. */
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

    /** The highlights of library document [id], live; nothing for a transient document, which has no row to hang them from. */
    fun observeHighlights(id: Long): Flow<List<HighlightEntity>> =
        if (id <= 0) flowOf(emptyList()) else highlightDao.observe(id)

    /** Stores one highlight per page of [boxesByPage], each page's line boxes in page-relative units. */
    suspend fun addHighlights(id: Long, boxesByPage: Map<Int, List<NormRect>>) {
        if (id <= 0 || boxesByPage.isEmpty()) return
        val rows = boxesByPage.map { (page, boxes) -> HighlightEntity(documentId = id, page = page, rects = encodeRects(boxes)) }
        withContext(Dispatchers.IO) { highlightDao.insert(rows) }
    }

    suspend fun deleteHighlights(ids: List<Long>) = withContext(Dispatchers.IO) {
        // Older SQLite builds cap a statement at 999 variables, so a very large removal goes in slices.
        ids.chunked(MAX_IDS_PER_STATEMENT).forEach { highlightDao.delete(it) }
    }

    /**
     * Stores the reader's [note] on highlight [id]; a blank note clears it and turns the mark back
     * into a plain highlight. Nothing from the document is written, only what the reader typed.
     */
    suspend fun setHighlightNote(id: Long, note: String) = withContext(Dispatchers.IO) {
        val cleaned = cleanHighlightNote(note)
        highlightDao.setNote(id, cleaned, (if (cleaned == null) HighlightKind.HIGHLIGHT else HighlightKind.NOTE).code)
    }

    /**
     * The outline read for [document] before, or null when there is none or it may no longer match:
     * a row is reused only while the file is the same size and the encoding's version is this build's.
     */
    suspend fun cachedOutline(document: DocumentEntity): List<OutlineEntry>? = withContext(Dispatchers.IO) {
        if (document.id <= 0) return@withContext null
        val row = pdfOutlineDao.get(document.id) ?: return@withContext null
        if (row.sourceSize != document.sizeBytes || row.cacheVersion != PDF_OUTLINE_CACHE_VERSION) {
            return@withContext null
        }
        decodeOutline(row.entries)
    }

    /**
     * Keeps [entries] for [document] so the next open does not read the bookmarks again. Only a
     * library row can hold one; the caller must not store an outline read from an unlocked copy,
     * since its titles come from a password-protected file.
     */
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

    private fun handOver(entity: DocumentEntity): Long {
        val id = nextHandedOverId--
        handedOver.clear()
        handedOver[id] = entity.copy(id = id)
        return id
    }

    suspend fun getById(id: Long): DocumentEntity? =
        if (id < 0) handedOver[id] else dao.getById(id)
    suspend fun markOpened(id: Long) = dao.touch(id, System.currentTimeMillis())
    suspend fun updateFormat(id: Long, format: DocumentFormat) = dao.updateFormat(id, format)

    /** Stars or unstars library documents. Only that one column is written: a star never touches the order, progress or bookmarks. */
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

    /**
     * Opens a document another app passed over without indexing it; the intent's read grant lasts
     * only for this entry.
     */
    suspend fun openHandedOver(uri: Uri): Long = withContext(Dispatchers.IO) {
        handOver(buildEntity(uri))
    }

    // Copies into filesDir/imports then indexes it; a failed insert deletes the copy again.
    // An encrypted PDF is copied as-is; the password is never read or kept here.
    suspend fun saveToLibrary(source: DocumentEntity): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // Autosave re-saves the same file on each open, so the duplicate check runs before copying.
        findSavedDuplicate(source)?.let { existing ->
            dao.touch(existing.id, now)
            return@withContext existing.id
        }
        val file = LibraryImport.copyToImports(context, source.uri.toUri())
        // A row can already stand on the URI this copy came from, with a grant that has since lapsed.
        // Saving re-points that row at the copy instead of adding a second entry, so the save outlives
        // the grant and the row keeps its id, star, position and bookmarks.
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

    /**
     * The library row already holding the document behind [source]: a same-title row whose app-owned
     * copy holds the same bytes. A row only on [source]'s own URI is not a match — it holds no copy,
     * so the save would not outlive the grant the document arrived with. Null when nothing matches or
     * the source cannot be read, so the save falls through to copy-and-insert.
     */
    private suspend fun findSavedDuplicate(source: DocumentEntity): DocumentEntity? {
        val sourceUri = source.uri.toUri()
        for (row in dao.getByTitle(source.title)) {
            val candidate = ownedCopy(row.uri) ?: continue
            if (LibraryImport.sameContent(candidate, source.sizeBytes) { openStream(sourceUri) }) return row
        }
        return null
    }

    /** The app-owned file behind [uri], or null for SAF and foreign URIs and missing files. */
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

    /**
     * SAF URIs share as-is. A local copy has no grant and cannot leave as a bare file:// URI, so it
     * goes through a FileProvider.
     */
    suspend fun resolveShareUri(uri: Uri): Uri = withContext(Dispatchers.IO) {
        if (uri.scheme != "file") return@withContext uri
        val path = uri.path ?: return@withContext uri
        val file = File(path)
        if (!LibraryImport.isInside(LibraryImport.importsDir(context), file)) return@withContext uri
        FileProvider.getUriForFile(context, "${context.packageName}$FILE_PROVIDER_SUFFIX", file)
    }

    /**
     * What opening the PDF at [uri] needs: the verdict [register] reached, if any, else a fresh check.
     */
    suspend fun pdfEncryption(uri: Uri): PdfEncryption = withContext(Dispatchers.IO) {
        knownPdfEncryption.remove(uri.toString()) ?: PdfEncryption.status { openStream(uri) }
    }

    /** Readability of a stored document, for the share action's pre-flight check. */
    suspend fun probeShare(document: DocumentEntity): SafAccess =
        withContext(Dispatchers.IO) { SafStorage.probeAccess(resolver, document.uri.toUri()) }

    /** Provider's live MIME type, not a stored copy; null when it reports none or the grant is gone. */
    suspend fun mimeTypeOf(document: DocumentEntity): String? =
        withContext(Dispatchers.IO) { SafStorage.queryMimeType(resolver, document.uri.toUri()) }

    /** Type the share declares: the provider's live answer resolved against the detected format. */
    suspend fun shareTypeFor(document: DocumentEntity): String =
        shareMimeType(document.format, mimeTypeOf(document))

    /**
     * A SAF grant can only come from the user picking the file again, so the re-pick must match
     * [matchesRepick]: same name and format.
     */
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

    /** Sniffs the file head on UNKNOWN, so a .conf or extension-less script still opens as text. */
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

        /** Formats whose encryption this app can detect; others are indexed and fail on open. */
        val ENCRYPTABLE = setOf(
            DocumentFormat.PDF,
            DocumentFormat.DOCX,
            DocumentFormat.ODT,
            DocumentFormat.DOC,
        )
    }
}
