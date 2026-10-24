package com.papyrus.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

/**
 * How one document's text ended up in the index. Only terminal states are stored: no row at all
 * means "not indexed yet", so a crash mid-extraction simply queues the document again next start.
 *
 * A document is not retried on every launch; the indexer's `INDEX_VERSION` is bumped to re-index
 * the whole library, which is the deliberate retry mechanism for a [FAILED] row.
 */
enum class TextIndexState {
    /** Text was extracted and is searchable (possibly empty, as a scanned PDF's is). */
    INDEXED,

    /** Extraction threw: malformed file, dead provider, a parser that gave up. */
    FAILED,

    /** Nothing to index on purpose: encrypted, DRM-protected, or a format with no reader. */
    SKIPPED,

    /** The source file is above [DocumentTextExtractor.MAX_SOURCE_BYTES], so it was never opened. */
    TOO_LARGE,
}

/**
 * One document's extracted text, the content table behind the FTS index.
 *
 * The primary key column is named `rowid` because it doubles as the FTS table's `docid`: an
 * external-content FTS4 table reads its text from here and stores only the inverted index, so a
 * document's words are never kept twice. `autoGenerate` is only here to make Room emit an inline
 * `INTEGER PRIMARY KEY` (a real rowid alias, which is what the FTS synchronization relies on); the
 * id is always supplied by the indexer, never generated. `documentId` stays the Kotlin name, so
 * callers never see the SQLite detail.
 */
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
)

/**
 * The inverted index over [DocumentTextEntity.text]; it holds no copy of the text.
 *
 * Only `text` is indexed. Writes go to `document_text` alone: Room's content-sync triggers keep this
 * table current, which is why [SearchDao] exposes no insert or update for it.
 *
 * The `rowid` field is required by Room for an external-content FTS entity (the generated triggers
 * need it to write the `docid`), and it is never set by us: the triggers always take it from the
 * content row's own `rowid`.
 */
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
