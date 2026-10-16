package com.papyrus.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A reader's bookmark inside one library document. Transient documents (negative ids) never get one.
 *
 * The location is the same three numbers as [ReadingProgressEntity], so a bookmark jumps through the
 * same code path as a restored position. The [label] is either typed by the reader or positional
 * ("Page 12"): never an excerpt of the text, so an unlocked password-protected document leaves nothing
 * readable behind.
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
