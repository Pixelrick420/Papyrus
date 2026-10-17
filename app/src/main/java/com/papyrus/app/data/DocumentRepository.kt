package com.papyrus.app.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.papyrus.app.viewer.OfficeEncryption
import com.papyrus.app.viewer.PdfEncryption
import com.papyrus.app.viewer.TextSniffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

class DocumentRepository(
    private val context: Context,
    private val dao: DocumentDao,
    private val progressDao: ReadingProgressDao,
    private val settings: SettingsRepository,
) {
    private val resolver get() = context.contentResolver

    /** In-memory only; ids are negative (Room's positive), -1 the viewer's sentinel, so counting starts at -2. */
    private val handedOver = mutableMapOf<Long, DocumentEntity>()
    private var nextHandedOverId = -2L

    /**
     * The encryption verdict [register] reached for a PDF it then handed over, so the viewer that opens
     * it next does not run the same whole-file check again. Read once and then forgotten, so a re-pick
     * or a retry asks afresh, and cleared when it grows past a handful of never-opened entries.
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

    /** Stores [entity] transiently under a fresh negative id; the caller opens the returned id. */
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

    suspend fun register(uri: Uri): Long = withContext(Dispatchers.IO) {
        val entity = buildEntity(uri)
        // Not indexed by default: a row would put it in the library and persist a grant past the open.
        // With autosave encrypted on, it is indexed like any file, and the password is asked again on open.
        if (entity.format in ENCRYPTABLE && !settings.current.autosaveEncrypted && isEncrypted(entity.format, uri)) {
            return@withContext handOver(entity)
        }
        SafStorage.persistPermission(resolver, uri)
        // buildEntity stamps lastOpenedAt, and upsertByUri carries it onto an existing row too.
        dao.upsertByUri(entity)
    }

    /** Opens a document another app passed over without indexing it; the intent's read grant lasts only for this entry. */
    suspend fun openHandedOver(uri: Uri): Long = withContext(Dispatchers.IO) {
        handOver(buildEntity(uri))
    }

    // Copies into filesDir/imports then indexes it; a failed insert deletes the copy again.
    // An encrypted PDF is copied as-is; the password is never read or kept here.
    suspend fun saveToLibrary(source: DocumentEntity): Long = withContext(Dispatchers.IO) {
        val file = LibraryImport.copyToImports(context, source.uri.toUri())
        val now = System.currentTimeMillis()
        // Autosave saves the same file every time it is opened, so an identical copy is reused, not added again.
        findSavedDuplicate(source.title, file)?.let { existing ->
            file.delete()
            dao.touch(existing.id, now)
            return@withContext existing.id
        }
        val row = source.copy(
            id = 0,
            uri = file.toUri().toString(),
            sizeBytes = file.length(),
            createdAt = now,
            lastOpenedAt = now,
        )
        var indexed = false
        try {
            dao.upsertByUri(row).also { indexed = true }
        } finally {
            if (!indexed) file.delete()
        }
    }

    /** A library row whose app-owned copy has [title] and exactly the bytes of [copy], the file just made. */
    private suspend fun findSavedDuplicate(title: String, copy: File): DocumentEntity? {
        val candidates = LibraryImport.importsDir(context).listFiles().orEmpty()
        for (candidate in candidates) {
            if (candidate == copy || !candidate.isFile || !LibraryImport.sameContent(candidate, copy)) continue
            val row = dao.getByUri(candidate.toUri().toString()) ?: continue
            if (row.title == title) return row
        }
        return null
    }

    /** Removes only the index entry — never the user's own file; an app-owned copy goes with its row. */
    suspend fun remove(document: DocumentEntity) = withContext(Dispatchers.IO) {
        SafStorage.releasePermission(resolver, document.uri.toUri())
        dao.deleteById(document.id)
        LibraryImport.deleteOwnedCopy(context, document.uri.toUri())
    }

    /** SAF URIs share as-is; a local copy has no grant and cannot leave as a bare file:// URI, so it uses a FileProvider. */
    suspend fun resolveShareUri(uri: Uri): Uri = withContext(Dispatchers.IO) {
        if (uri.scheme != "file") return@withContext uri
        val path = uri.path ?: return@withContext uri
        val file = File(path)
        if (!LibraryImport.isInside(LibraryImport.importsDir(context), file)) return@withContext uri
        FileProvider.getUriForFile(context, "${context.packageName}$FILE_PROVIDER_SUFFIX", file)
    }

    /** What opening the PDF at [uri] needs: the verdict [register] already reached if there is one, else a fresh check. */
    suspend fun pdfEncryption(uri: Uri): PdfEncryption = withContext(Dispatchers.IO) {
        knownPdfEncryption.remove(uri.toString()) ?: PdfEncryption.status { openStream(uri) }
    }

    /** Readability of a stored document, for the share action's pre-flight check. */
    suspend fun probeShare(document: DocumentEntity): SafAccess =
        withContext(Dispatchers.IO) { SafStorage.probeAccess(resolver, document.uri.toUri()) }

    /** The provider's current MIME type, not a stored copy; null when it reports none or the grant is gone. */
    suspend fun mimeTypeOf(document: DocumentEntity): String? =
        withContext(Dispatchers.IO) { SafStorage.queryMimeType(resolver, document.uri.toUri()) }

    /** The type the share action declares: the provider's live answer, resolved against the detected format. */
    suspend fun shareTypeFor(document: DocumentEntity): String =
        shareMimeType(document.format, mimeTypeOf(document))

    /** A SAF grant can only come from the user picking the file again, so the re-pick must be the same name and format ([matchesRepick]). */
    suspend fun regrant(expected: DocumentEntity, picked: Uri): Boolean = withContext(Dispatchers.IO) {
        val meta = SafStorage.queryMetadata(context, picked)
        val pickedFormat = resolveFormat(picked, meta.displayName, meta.mimeType)
        if (!matchesRepick(expected.title, expected.format, meta.displayName, pickedFormat)) return@withContext false
        SafStorage.persistPermission(resolver, picked)
        // A handed-over document has no row to re-point, so the in-memory entry carries the new URI.
        if (expected.id < 0) {
            handedOver[expected.id] = expected.copy(uri = picked.toString())
            return@withContext true
        }
        dao.updateUri(expected.id, picked.toString())
        true
    }

    /** An unreadable file reads as not encrypted, so it fails in the renderer rather than prompting. */
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

    /** Falls back to sniffing the file head on UNKNOWN, so a .conf or extension-less script still opens as text. */
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

        /** Formats whose encryption this app can detect; others are indexed and fail on open. */
        val ENCRYPTABLE = setOf(
            DocumentFormat.PDF,
            DocumentFormat.DOCX,
            DocumentFormat.ODT,
            DocumentFormat.DOC,
        )
    }
}
