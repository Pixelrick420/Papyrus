package com.papyrus.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {

    @Query("SELECT * FROM documents ORDER BY lastOpenedAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>

    @Transaction
    @Query("SELECT * FROM documents WHERE id = :id")
    fun observeWithPages(id: Long): Flow<DocumentWithPages?>

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getById(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): DocumentEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(document: DocumentEntity): Long

    @Update
    suspend fun update(document: DocumentEntity)

    @Query("UPDATE documents SET lastOpenedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: Long, timestamp: Long)

    /** A SAF re-pick returns a new URI for the same document, so the row is re-bound to it, keeping its pages and history. */
    @Query("UPDATE documents SET uri = :uri WHERE id = :id")
    suspend fun updateUri(id: Long, uri: String)

    @Query("UPDATE documents SET pageCount = :count WHERE id = :id")
    suspend fun updatePageCount(id: Long, count: Int)

    /** Sniffing can only resolve a format once the row exists, so this runs after insert. */
    @Query("UPDATE documents SET format = :format WHERE id = :id")
    suspend fun updateFormat(id: Long, format: DocumentFormat)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Insert
    suspend fun insertPages(pages: List<ScanPageEntity>)

    @Query("SELECT * FROM scan_pages WHERE documentId = :documentId ORDER BY position ASC")
    suspend fun getPages(documentId: Long): List<ScanPageEntity>

    @Query("DELETE FROM scan_pages WHERE documentId = :documentId")
    suspend fun deletePages(documentId: Long)

    @Query("UPDATE scan_pages SET position = :position WHERE id = :pageId")
    suspend fun setPagePosition(pageId: Long, position: Int)

    @Transaction
    suspend fun upsertByUri(document: DocumentEntity): Long {
        val existing = getByUri(document.uri) ?: return insertIgnore(document)
        update(
            existing.copy(
                title = document.title,
                mimeType = document.mimeType,
                format = document.format,
                sizeBytes = document.sizeBytes,
            ),
        )
        return existing.id
    }

    @Transaction
    suspend fun insertWithPages(document: DocumentEntity, pages: List<ScanPageEntity>): Long {
        val id = upsertByUri(document)
        deletePages(id)
        insertPages(pages.mapIndexed { index, page -> page.copy(id = 0, documentId = id, position = index) })
        return id
    }

    @Transaction
    suspend fun reorderPages(orderedPageIds: List<Long>) {
        orderedPageIds.forEachIndexed { index, pageId -> setPagePosition(pageId, index) }
    }
}
