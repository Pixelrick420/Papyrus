package com.papyrus.app.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class RawContentMatch(
    val documentId: Long,
    val matchInfo: ByteArray,
)

// Lets the viewer know whether a document has words without reading a whole PDF.
data class TextSummaryRow(
    val state: TextIndexState,
    val charCount: Int,
    /** The file's size when its text was read: a different size means the row is for an older copy. */
    val sourceSize: Long,
)

data class IndexedDocument(
    @Embedded val document: DocumentEntity,
    val sourceModified: Long,
)

data class SnippetRow(
    val documentId: Long,
    val snippet: String?,
)

// Writes go to `document_text` only; Room's triggers maintain the FTS table.
@Dao
interface SearchDao {

    // Includes rows written by an older index version or for a file whose size changed. Bounded by [limit] so one pass never holds the whole library.
    @Query(
        "SELECT d.* FROM documents d LEFT JOIN document_text t ON t.rowid = d.id " +
            "WHERE t.rowid IS NULL OR t.indexVersion < :version OR t.sourceSize != d.sizeBytes " +
            "ORDER BY d.id LIMIT :limit",
    )
    suspend fun pending(version: Int, limit: Int): List<DocumentEntity>

    // Narrowed by size so large files cannot hide small ones.
    @Query(
        "SELECT d.* FROM documents d LEFT JOIN document_text t ON t.rowid = d.id " +
            "WHERE (t.rowid IS NULL OR t.indexVersion < :version OR t.sourceSize != d.sizeBytes) " +
            "AND d.sizeBytes <= :maxBytes " +
            "ORDER BY d.id LIMIT :limit",
    )
    suspend fun pendingUpTo(version: Int, maxBytes: Long, limit: Int): List<DocumentEntity>

    // Paged by id rather than offset because the pass rewrites rows as it goes.
    @Query(
        "SELECT d.*, t.sourceModified AS sourceModified FROM documents d " +
            "JOIN document_text t ON t.rowid = d.id " +
            "WHERE t.indexVersion = :version AND t.sourceModified != 0 AND d.id > :afterId " +
            "ORDER BY d.id LIMIT :limit",
    )
    suspend fun indexedWithModified(version: Int, afterId: Long, limit: Int): List<IndexedDocument>

    @Query("SELECT id FROM documents WHERE id = :id")
    suspend fun existingId(id: Long): Long?

    // Reads only the summary columns, never the text.
    @Query("SELECT state, charCount, sourceSize FROM document_text WHERE rowid = :id")
    fun observeTextSummary(id: Long): Flow<List<TextSummaryRow>>

    @Insert
    suspend fun insertText(row: DocumentTextEntity)

    @Query("DELETE FROM document_text WHERE rowid = :id")
    suspend fun deleteText(id: Long)

    // Never REPLACE: it skips the delete triggers unless recursive triggers are on, leaving the document searchable by its old text.
    @Transaction
    suspend fun replace(row: DocumentTextEntity) {
        deleteText(row.documentId)
        insertText(row)
    }

    // Deliberately unlimited: a limit would pick candidates by rowid rather than relevance and could drop the best match before scoring.
    @Query(
        "SELECT document_text_fts.rowid AS documentId, " +
            "matchinfo(document_text_fts, 'pcnalx') AS matchInfo " +
            "FROM document_text_fts " +
            "JOIN document_text ON document_text.rowid = document_text_fts.rowid " +
            "WHERE document_text_fts MATCH :match",
    )
    suspend fun candidates(match: String): List<RawContentMatch>

    // The marker characters are duplicated as [SNIPPET_START] and [SNIPPET_END]; Room cannot template a query, so keep them in step by hand.
    @Query(
        "SELECT document_text_fts.rowid AS documentId, " +
            "snippet(document_text_fts, char(1), char(2), '…', -1, 32) AS snippet " +
            "FROM document_text_fts " +
            "WHERE document_text_fts MATCH :match AND document_text_fts.rowid IN (:ids)",
    )
    suspend fun snippets(match: String, ids: List<Long>): List<SnippetRow>

    companion object {
        const val SNIPPET_START = '\u0001'

        const val SNIPPET_END = '\u0002'
    }
}
