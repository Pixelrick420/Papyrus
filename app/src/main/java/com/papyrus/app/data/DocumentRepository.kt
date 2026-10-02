package com.papyrus.app.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.papyrus.app.scanner.ScanFilter
import com.papyrus.app.viewer.TextSniffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class DocumentRepository(
    private val context: Context,
    private val dao: DocumentDao,
) {
    private val resolver get() = context.contentResolver

    val documents: Flow<List<DocumentEntity>> = dao.observeAll()

    suspend fun getById(id: Long): DocumentEntity? = dao.getById(id)
    suspend fun markOpened(id: Long) = dao.touch(id, System.currentTimeMillis())
    suspend fun updatePageCount(id: Long, count: Int) = dao.updatePageCount(id, count)
    suspend fun updateFormat(id: Long, format: DocumentFormat) = dao.updateFormat(id, format)

    suspend fun register(uri: Uri): Long = withContext(Dispatchers.IO) {
        SafStorage.persistPermission(resolver, uri)
        val id = dao.upsertByUri(buildEntity(uri))
        dao.touch(id, System.currentTimeMillis())
        id
    }

    /** Takes a write grant too, unlike register: the export path deletes and rewrites the file as pages are captured. */
    suspend fun registerScan(uri: Uri, pageCount: Int, filter: ScanFilter): Long = withContext(Dispatchers.IO) {
        SafStorage.persistPermission(resolver, uri, write = true)
        val entity = buildEntity(uri).copy(format = DocumentFormat.PDF, pageCount = pageCount)
        val pages = List(pageCount) { ScanPageEntity(documentId = 0, position = it, filter = filter) }
        dao.insertWithPages(entity, pages)
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
