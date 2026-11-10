package com.papyrus.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

// Only terminal states are stored: no row means "not indexed yet", so a crash mid-extraction requeues the document.
// Bump the indexer's INDEX_VERSION to retry [FAILED] rows.
enum class TextIndexState {
    INDEXED,

    FAILED,

    SKIPPED,

    TOO_LARGE,
}

// `rowid` doubles as the FTS table's `docid`; autoGenerate only makes Room emit an inline INTEGER PRIMARY KEY (a real rowid alias).
// The indexer always supplies the id.
@Entity(
    tableName = "document_text",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["rowid"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DocumentTextEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "rowid")
    val documentId: Long,
    val text: String,
    val state: TextIndexState,
    val charCount: Int,
    val sourceSize: Long,
    val indexedAt: Long,
    val indexVersion: Int,
    // 0 when the provider reported no modified time; compared to detect edits without opening the file.
    @ColumnInfo(defaultValue = "0")
    val sourceModified: Long = 0,
)

// Room's content-sync triggers keep this current, so [SearchDao] has no insert or update; Room requires `rowid` and we never set it.
@Fts4(
    contentEntity = DocumentTextEntity::class,
    tokenizer = FtsOptions.TOKENIZER_UNICODE61,
    tokenizerArgs = ["remove_diacritics=1"],
    prefix = [2, 3],
)
@Entity(tableName = "document_text_fts")
data class DocumentTextFtsEntity(
    @PrimaryKey
    @ColumnInfo(name = "rowid")
    val rowid: Long,
    val text: String,
)
