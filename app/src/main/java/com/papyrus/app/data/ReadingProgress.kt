package com.papyrus.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

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
