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

    /** A SAF re-pick returns a new URI for the same document, so the row is re-bound to it, keeping its history. */
    @Query("UPDATE documents SET uri = :uri WHERE id = :id")
    suspend fun updateUri(id: Long, uri: String)

    /** Sniffing can only resolve a format once the row exists, so this runs after insert. */
    @Query("UPDATE documents SET format = :format WHERE id = :id")
    suspend fun updateFormat(id: Long, format: DocumentFormat)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Registering a document counts as opening it, so the row is stamped here, in the same write,
     * instead of by a second write that would make the library query run again.
     */
    @Transaction
    suspend fun upsertByUri(document: DocumentEntity): Long {
        val existing = getByUri(document.uri) ?: return insertIgnore(document)
        update(
            existing.copy(
                title = document.title,
                format = document.format,
                sizeBytes = document.sizeBytes,
                lastOpenedAt = document.lastOpenedAt,
            ),
        )
        return existing.id
    }
}
