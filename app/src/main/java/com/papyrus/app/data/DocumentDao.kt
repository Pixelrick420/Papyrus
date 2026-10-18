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

    /** The duplicate check reads only rows that could match, so title is a query, not a scan. */
    @Query("SELECT * FROM documents WHERE title = :title")
    suspend fun getByTitle(title: String): List<DocumentEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(document: DocumentEntity): Long

    @Update
    suspend fun update(document: DocumentEntity)

    @Query("UPDATE documents SET lastOpenedAt = :timestamp WHERE id = :id")
    suspend fun touch(id: Long, timestamp: Long)

    /** Re-binds the row to a new URI: SAF re-picking the same document returns a different one. */
    @Query("UPDATE documents SET uri = :uri WHERE id = :id")
    suspend fun updateUri(id: Long, uri: String)

    /** Sniffing needs the row to exist, so this runs after insert. */
    @Query("UPDATE documents SET format = :format WHERE id = :id")
    suspend fun updateFormat(id: Long, format: DocumentFormat)

    /** One statement for any number of rows; an id that is gone is simply not updated. */
    @Query("UPDATE documents SET isFavourite = :favourite WHERE id IN (:ids)")
    suspend fun setFavourite(ids: List<Long>, favourite: Boolean)

    @Query("DELETE FROM documents WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Registering counts as opening, so the row is stamped here in the same write
     * instead of by a second write that would re-run the library query.
     */
    @Transaction
    suspend fun upsertByUri(document: DocumentEntity): Long {
        val existing = getByUri(document.uri) ?: return insertIgnore(document)
        update(existing.reopenedWith(document))
        return existing.id
    }
}
