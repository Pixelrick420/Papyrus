package com.papyrus.app.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Where a library document was last read: one row per document, kept in its own table.
 *
 * **Why a separate table.** A reading position is written every time the reader stops scrolling. Room
 * re-runs every query that reads a table when that table changes, so putting these columns on
 * `documents` would re-emit Home's whole list on each save and fight the list's own animations. Here a
 * save touches only this table, and `lastOpenedAt` (which orders Home) is never written at all.
 *
 * **Numbers only.** The anchor ([locationKind], [locationUnit], [locationFraction]), the zoom and the
 * derived progress are all numbers, so a password-protected document that was unlocked leaves no
 * plaintext fragment in the database.
 *
 * The row goes with its document (`ON DELETE CASCADE`). Documents are never written with
 * `OnConflictStrategy.REPLACE`, because a replace is a delete plus an insert and would cascade away
 * this row.
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
    /** [LocationKind.code]. Stored as a number so an unknown future code can be ignored, never crash a read. */
    val locationKind: Int,
    val locationUnit: Int,
    val locationFraction: Float,
    /** How many units the document had when this was saved; lets Home say "p. 12 of 200" without opening it. */
    val unitCount: Int,
    /** The committed zoom scale. */
    val zoomScale: Float,
    /** 0..1, derived from the location and [unitCount] when saved. */
    val progress: Float,
    val updatedAt: Long,
)

/**
 * The columns Home needs from a stored position: enough for a progress bar and "p. 12 of 200", and
 * nothing that changes on every save. `updatedAt` and the zoom are left out on purpose, so a save that
 * does not move the progress is an equal value and [DocumentRepository.readingProgress] does not
 * re-emit it.
 */
data class ReadingProgressSummary(
    val documentId: Long,
    val locationKind: Int,
    val locationUnit: Int,
    val unitCount: Int,
    val progress: Float,
) {
    val label: ProgressLabel get() = progressLabel(locationKind, locationUnit, unitCount, progress)
}

@Dao
interface ReadingProgressDao {

    @Query("SELECT * FROM reading_progress WHERE documentId = :documentId")
    suspend fun get(documentId: Long): ReadingProgressEntity?

    @Query("SELECT documentId, locationKind, locationUnit, unitCount, progress FROM reading_progress")
    fun observeSummaries(): Flow<List<ReadingProgressSummary>>

    /**
     * Writes one position, replacing the document's previous one. A replace is safe here: nothing
     * references this table, so it cascades into nothing (unlike `documents`, which is never replaced).
     *
     * The `WHERE EXISTS` makes a save for a document that was removed in the meantime a no-op instead
     * of a foreign-key failure on a background coroutine.
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

    @Query("DELETE FROM reading_progress WHERE documentId = :documentId")
    suspend fun reset(documentId: Long)
}
