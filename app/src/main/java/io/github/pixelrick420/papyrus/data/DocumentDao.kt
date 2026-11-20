package io.github.pixelrick420.papyrus.data

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

    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getById(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents WHERE uri = :uri LIMIT 1")
    suspend fun getByUri(uri: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE title = :title")
    suspend fun getByTitle(title: String): List<DocumentEntity>

    @Query("SELECT uri FROM documents")
    suspend fun allUris(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(document: DocumentEntity): Long

    @Update
    suspend fun update(document: DocumentEntity)

    @Query("UPDATE documents SET lastOpenedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: Long, timestamp: Long)

    /** Re-binds the row to a new URI: SAF re-picking the same document returns a different one. */
    @Query("UPDATE documents SET uri = :uri WHERE id = :id")
    suspend fun updateUri(id: Long, uri: String)

    /** Takes the URI only when it is free, and releases the old grant in the same transaction. */
    @Transaction
    suspend fun rebindUri(
        id: Long,
        uri: String,
        oldUri: String?,
        releaseGrant: (String) -> Unit,
    ): Boolean {
        val owner = getByUri(uri)
        if (owner != null && owner.id != id) return false
        updateUri(id, uri)
        if (oldUri != null && oldUri != uri) releaseGrant(oldUri)
        return true
    }

    /** Sniffing needs the row to exist, so this runs after insert. */
    @Query("UPDATE documents SET format = :format WHERE id = :id")
    suspend fun updateFormat(id: Long, format: DocumentFormat)

    @Query("UPDATE documents SET isFavourite = :starred WHERE id IN (:ids)")
    suspend fun setStarred(ids: List<Long>, starred: Boolean)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM document_text WHERE rowid = :id")
    suspend fun deleteText(id: Long)

    /** The FTS delete trigger needs a real DELETE of the text row before the document row goes. */
    @Transaction
    suspend fun deleteDocument(id: Long) {
        deleteText(id)
        deleteById(id)
    }

    // Stamps the open time in the same write, avoiding a second write that would re-run the library query.
    @Transaction
    suspend fun upsertByUri(document: DocumentEntity): Long {
        val existing = getByUri(document.uri) ?: return insertIgnore(document)
        update(existing.reopenedWith(document))
        return existing.id
    }
}
