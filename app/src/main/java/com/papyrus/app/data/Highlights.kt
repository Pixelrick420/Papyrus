package com.papyrus.app.data

import androidx.room.ColumnInfo
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
 * (see [encodeRects]), so the row has no page text: an unlocked document leaves the page's plaintext
 * out of the database. [note] holds the reader's own words about the run, nothing from the file.
 * Transient documents (negative ids) never get one.
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
    /** [HighlightKind.code]; the default is the value every row written before notes existed has. */
    @ColumnInfo(defaultValue = "1") val kind: Int = HighlightKind.HIGHLIGHT.code,
    /** The note the reader typed, or null for a plain highlight; added in schema version 8. */
    val note: String? = null,
)

internal fun HighlightEntity.toHighlight() =
    Highlight(id, page, decodeRects(rects), HighlightKind.fromCode(kind), note)

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

    /** The note and the kind move together: clearing a note turns the mark back into a highlight. */
    @Query("UPDATE highlights SET note = :note, kind = :kind WHERE id = :id")
    suspend fun setNote(id: Long, note: String?, kind: Int)
}
