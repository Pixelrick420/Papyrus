package com.papyrus.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

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
