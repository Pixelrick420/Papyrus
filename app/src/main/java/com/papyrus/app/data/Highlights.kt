package com.papyrus.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A reader's highlight on one page of a library PDF; one that crosses a page break is a row per page.
 * Only an overlay is stored and the file is never written to. [rects] holds the line boxes of the run
 * (see [encodeRects]), so the row has geometry and no text: an unlocked document leaves no plaintext
 * in the database. Transient documents (negative ids) never get one.
 */
@Entity(
    tableName = "highlights",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["documentId"])],
)
data class HighlightEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val page: Int,
    val rects: String,
)

internal fun HighlightEntity.toHighlight() = Highlight(id, page, decodeRects(rects))

@Dao
interface HighlightDao {

    /** Scoped to one document, so a highlight added elsewhere never re-emits this list. */
    @Query("SELECT * FROM highlights WHERE documentId = :documentId ORDER BY id")
    fun observe(documentId: Long): Flow<List<HighlightEntity>>

    /** Fails with a foreign-key error for a document that is gone, as a bookmark insert does. */
    @Insert
    suspend fun insert(rows: List<HighlightEntity>)

    @Query("DELETE FROM highlights WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}
