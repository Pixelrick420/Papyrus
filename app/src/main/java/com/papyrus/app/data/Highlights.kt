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

// Only an overlay is stored, never the file; [rects] hold boxes, not text, so an unlocked document leaves no plaintext in the database.
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
    // Defaults to the value every row written before notes existed has.
    @ColumnInfo(defaultValue = "1") val kind: Int = HighlightKind.HIGHLIGHT.code,
    val note: String? = null,
)

internal fun HighlightEntity.toHighlight() =
    Highlight(id, page, decodeRects(rects), HighlightKind.fromCode(kind), note)

@Dao
interface HighlightDao {

    @Query("SELECT * FROM highlights WHERE documentId = :documentId ORDER BY id")
    fun observe(documentId: Long): Flow<List<HighlightEntity>>

    @Insert
    suspend fun insert(rows: List<HighlightEntity>)

    @Query("DELETE FROM highlights WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    /** The note and the kind move together: clearing a note turns the mark back into a highlight. */
    @Query("UPDATE highlights SET note = :note, kind = :kind WHERE id = :id")
    suspend fun setNote(id: Long, note: String?, kind: Int)
}
