package com.papyrus.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Where a document was last read, one row per document in its own table: a save here never re-emits
 * Home's list, and the numbers-only anchor leaves no plaintext of an unlocked document behind.
 */
@Entity(
    tableName = "reading_progress",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReadingProgressEntity(
    /** The primary key doubles as the index the foreign key needs. */
    @PrimaryKey val documentId: Long,
    /** [LocationKind.code]; a number so an unknown future code is ignored, never crash a read. */
    val locationKind: Int,
    val locationUnit: Int,
    val locationFraction: Float,
    /** Units when saved; lets a restore clamp the anchor without opening the document. */
    val unitCount: Int,
    val zoomScale: Float,
    /** 0..1, derived when saved. */
    val progress: Float,
    val updatedAt: Long,
)

@Dao
interface ReadingProgressDao {

    @Query("SELECT * FROM reading_progress WHERE documentId = :documentId")
    suspend fun get(documentId: Long): ReadingProgressEntity?

    /**
     * Replaces the document's previous position; safe because nothing references this table.
     * `WHERE EXISTS` makes a save for a meantime-removed document a no-op, not a foreign-key failure.
     */
    @Query(
        "INSERT OR REPLACE INTO reading_progress " +
            "(documentId, locationKind, locationUnit, locationFraction, unitCount, zoomScale, progress, updatedAt) " +
            "SELECT :documentId, :locationKind, :locationUnit, :locationFraction, :unitCount, :zoomScale, :progress, :updatedAt " +
            "WHERE EXISTS (SELECT 1 FROM documents WHERE id = :documentId)",
    )
    suspend fun save(
        documentId: Long,
        locationKind: Int,
        locationUnit: Int,
        locationFraction: Float,
        unitCount: Int,
        zoomScale: Float,
        progress: Float,
        updatedAt: Long,
    )
}
