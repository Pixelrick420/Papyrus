package com.papyrus.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * A reader's bookmark in one library document; transient documents (negative ids) never get one.
 * [label] is typed or positional ("Page 12"), never an excerpt: an unlocked document leaks no text.
 */
@Entity(
    tableName = "bookmarks",
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
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val locationKind: Int,
    val locationUnit: Int,
    val locationFraction: Float,
    val label: String,
    val createdAt: Long,
)

@Dao
interface BookmarkDao {

    /**
     * Scoped to one document, so a bookmark added elsewhere never re-emits this list. The order is a
     * convenience: the screen orders again with [forFormat], which also drops rows of a foreign kind.
     */
    @Query(
        "SELECT * FROM bookmarks WHERE documentId = :documentId " +
            "ORDER BY locationUnit, locationFraction, createdAt, id",
    )
    fun observe(documentId: Long): Flow<List<BookmarkEntity>>

    @Query("SELECT * FROM bookmarks WHERE documentId = :documentId")
    suspend fun getAll(documentId: Long): List<BookmarkEntity>

    /** Fails with a foreign-key error for a document that is gone; [addIfAllowed] checks first. */
    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Query("UPDATE bookmarks SET label = :label WHERE id = :id")
    suspend fun rename(id: Long, label: String)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Puts a deleted bookmark back under its old id, for Undo. `WHERE EXISTS` turns a document removed in
     * the meantime into a no-op instead of a foreign-key failure, and `OR IGNORE` an id that is back already.
     * Only this table is written: nothing here can touch `documents` or what cascades from it.
     */
    @Query(
        "INSERT OR IGNORE INTO bookmarks (id, documentId, locationKind, locationUnit, locationFraction, label, createdAt) " +
            "SELECT :id, :documentId, :locationKind, :locationUnit, :locationFraction, :label, :createdAt " +
            "WHERE EXISTS (SELECT 1 FROM documents WHERE id = :documentId)",
    )
    suspend fun restore(
        id: Long,
        documentId: Long,
        locationKind: Int,
        locationUnit: Int,
        locationFraction: Float,
        label: String,
        createdAt: Long,
    )

    /**
     * Adds a bookmark unless the spot is bookmarked already or the document is full. Read and write share
     * one transaction, so two quick taps cannot both pass the check.
     */
    @Transaction
    suspend fun addIfAllowed(
        documentId: Long,
        location: ReadingLocation,
        label: String,
        createdAt: Long,
        limit: Int,
    ): BookmarkAdd {
        val existing = getAll(documentId).mapNotNull { it.toLocation() }
        return when (decideBookmark(existing, location, limit)) {
            BookmarkDecision.ALREADY_BOOKMARKED -> BookmarkAdd.AlreadyBookmarked
            BookmarkDecision.LIMIT_REACHED -> BookmarkAdd.LimitReached
            BookmarkDecision.ADD -> {
                val row = BookmarkEntity(
                    documentId = documentId,
                    locationKind = location.kind.code,
                    locationUnit = location.unit,
                    locationFraction = location.fraction,
                    label = label,
                    createdAt = createdAt,
                )
                BookmarkAdd.Added(row.copy(id = insert(row)))
            }
        }
    }
}
