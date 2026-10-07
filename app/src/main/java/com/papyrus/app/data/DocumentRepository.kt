package com.papyrus.app.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.papyrus.app.viewer.TextSniffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class DocumentRepository(
    private val context: Context,
    private val dao: DocumentDao,
) {
    private val resolver get() = context.contentResolver

    /**
     * In-memory only; lives as long as the process. Ids are negative (Room's are positive), -1 is the
     * viewer's "no document" sentinel, so counting starts at -2; written only by [openHandedOver].
     */
    private val handedOver = mutableMapOf<Long, DocumentEntity>()
    private var nextHandedOverId = -2L

    val documents: Flow<List<DocumentEntity>> = dao.observeAll()

    suspend fun getById(id: Long): DocumentEntity? =
        if (id < 0) handedOver[id] else dao.getById(id)
    suspend fun markOpened(id: Long) = dao.touch(id, System.currentTimeMillis())
    suspend fun updateFormat(id: Long, format: DocumentFormat) = dao.updateFormat(id, format)

    suspend fun register(uri: Uri): Long = withContext(Dispatchers.IO) {
        SafStorage.persistPermission(resolver, uri)
        // buildEntity stamps lastOpenedAt, and upsertByUri carries it onto an existing row too.
        dao.upsertByUri(buildEntity(uri))
    }

    /**
     * Opens a document another app passed over, without indexing it: a row would either sit in the
     * library or need deleting again, and the intent's read grant lasts exactly as long as this entry.
     */
    suspend fun openHandedOver(uri: Uri): Long = withContext(Dispatchers.IO) {
        val id = nextHandedOverId--
        handedOver.clear()
        handedOver[id] = buildEntity(uri).copy(id = id)
        id
    }

    /** Copies into filesDir/imports, then indexes the copy — the row comes last, and a failed insert deletes the copy again. */
    suspend fun saveToLibrary(source: DocumentEntity): Long = withContext(Dispatchers.IO) {
        val file = LibraryImport.copyToImports(context, source.uri.toUri())
        val now = System.currentTimeMillis()
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

    /** Removes only the index entry — never the user's own file; an app-owned copy goes with its row. */
    suspend fun remove(document: DocumentEntity) = withContext(Dispatchers.IO) {
        SafStorage.releasePermission(resolver, document.uri.toUri())
        dao.deleteById(document.id)
        LibraryImport.deleteOwnedCopy(context, document.uri.toUri())
    }

    /**
     * SAF URIs share as-is (the row's persisted grant sub-grants); a local copy rides on no grant and
     * cannot leave as a bare file:// URI, so it goes through the FileProvider.
     */
    suspend fun resolveShareUri(uri: Uri): Uri = withContext(Dispatchers.IO) {
        if (uri.scheme != "file") return@withContext uri
        val path = uri.path ?: return@withContext uri
        val file = File(path)
        if (!LibraryImport.isInside(LibraryImport.importsDir(context), file)) return@withContext uri
        FileProvider.getUriForFile(context, "${context.packageName}$FILE_PROVIDER_SUFFIX", file)
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

    /** A SAF grant can only come from the user picking the file again, so the re-pick is matched by display name. */
    suspend fun regrant(expected: DocumentEntity, picked: Uri): Boolean = withContext(Dispatchers.IO) {
        val meta = SafStorage.queryMetadata(context, picked)
        if (!meta.displayName.startsWith(expected.title)) return@withContext false
        SafStorage.persistPermission(resolver, picked)
        // A handed-over document has no row to re-point, so the in-memory entry carries the new URI.
        if (expected.id < 0) {
            handedOver[expected.id] = expected.copy(uri = picked.toString())
            return@withContext true
        }
        dao.updateUri(expected.id, picked.toString())
        true
    }

    private suspend fun buildEntity(uri: Uri): DocumentEntity {
        val meta = SafStorage.queryMetadata(context, uri)
        val now = System.currentTimeMillis()
        return DocumentEntity(
            uri = uri.toString(),
            title = meta.displayName.ifBlank { "untitled" },
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
    }
}
