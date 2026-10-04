package com.papyrus.app.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.papyrus.app.viewer.TextSniffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class DocumentRepository(
    private val context: Context,
    private val dao: DocumentDao,
) {
    private val resolver get() = context.contentResolver

    /**
     * Documents another app handed over, held in memory only. The repository is an Application
     * singleton, so an entry lives exactly as long as the process.
     *
     * Ids are negative: Room's are positive and the viewer reads its subject as a plain Long off the
     * nav arguments. -1 is the view model's "no document" sentinel, so counting starts at -2. Written
     * only by [openHandedOver], which completes before the viewer that reads it is created.
     */
    private val handedOver = mutableMapOf<Long, DocumentEntity>()
    private var nextHandedOverId = -2L

    val documents: Flow<List<DocumentEntity>> = dao.observeAll()

    suspend fun getById(id: Long): DocumentEntity? =
        if (id < 0) handedOver[id] else dao.getById(id)
    suspend fun markOpened(id: Long) = dao.touch(id, System.currentTimeMillis())
    suspend fun updatePageCount(id: Long, count: Int) = dao.updatePageCount(id, count)
    suspend fun updateFormat(id: Long, format: DocumentFormat) = dao.updateFormat(id, format)

    suspend fun register(uri: Uri): Long = withContext(Dispatchers.IO) {
        SafStorage.persistPermission(resolver, uri)
        val id = dao.upsertByUri(buildEntity(uri))
        dao.touch(id, System.currentTimeMillis())
        id
    }

    /**
     * Opens a document another app passed over, without indexing it.
     *
     * No row on purpose: such a document belongs to the app that sent it, so a row would either sit
     * in the library or need deleting again. Nothing is persisted -- the read grant that arrived
     * with the intent lasts as long as the task receiving it, which is exactly how long this entry is
     * reachable. Only the newest is kept, since handing over another document pops the viewer.
     */
    suspend fun openHandedOver(uri: Uri): Long = withContext(Dispatchers.IO) {
        val id = nextHandedOverId--
        handedOver.clear()
        handedOver[id] = buildEntity(uri).copy(id = id)
        id
    }

    /** Removes the index entry only; the user's file is never deleted. */
    suspend fun remove(document: DocumentEntity) = withContext(Dispatchers.IO) {
        SafStorage.releasePermission(resolver, document.uri.toUri())
        dao.deleteById(document.id)
    }

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
            title = meta.displayName.substringBeforeLast('.').ifBlank { meta.displayName },
            mimeType = meta.mimeType,
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
}
