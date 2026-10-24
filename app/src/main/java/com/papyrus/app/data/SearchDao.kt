package com.papyrus.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/** One FTS candidate: the document and the raw `matchinfo` blob used to rank it. */
data class RawContentMatch(
    val documentId: Long,
    val matchInfo: ByteArray,
)

/**
 * One excerpt request's answer. [snippet] carries [SearchDao.SNIPPET_START] and
 * [SearchDao.SNIPPET_END] around each matched word.
 */
data class SnippetRow(
    val documentId: Long,
    val snippet: String?,
)

/**
 * The index's only write path and both read phases.
 *
 * Writes go to `document_text` exclusively: the external-content FTS table is maintained by Room's
 * generated triggers, so `document_text_fts` has no insert or update here.
 */
@Dao
interface SearchDao {

    /**
     * The next documents to index, oldest first: no text row, or one written by an older index
     * version. Bounded by [limit] so one pass never has to hold the whole library at once.
     */
    @Query(
        "SELECT d.* FROM documents d LEFT JOIN document_text t ON t.rowid = d.id " +
            "WHERE t.rowid IS NULL OR t.indexVersion < :version ORDER BY d.id LIMIT :limit",
    )
    suspend fun pending(version: Int, limit: Int): List<DocumentEntity>

    /** The row again if it is still in the library; the indexer re-checks before writing. */
    @Query("SELECT id FROM documents WHERE id = :id")
    suspend fun existingId(id: Long): Long?

    @Insert
    suspend fun insertText(row: DocumentTextEntity)

    @Query("DELETE FROM document_text WHERE rowid = :id")
    suspend fun deleteText(id: Long)

    /**
     * Deletes then inserts, never `REPLACE`: a REPLACE is a delete plus an insert at the SQLite level
     * but skips the delete triggers unless recursive triggers are on, which would leave the document
     * searchable by its old text. The explicit delete fires the FTS4 sync trigger first.
     */
    @Transaction
    suspend fun replace(row: DocumentTextEntity) {
        deleteText(row.documentId)
        insertText(row)
    }

    /**
     * Phase one of a search: every document matching [match], with the ranking data and no content
     * read. `matchinfo` reads only the inverted index, so this stays cheap on a large library.
     *
     * Deliberately unlimited, because a limit here would pick the candidates by rowid rather than
     * relevance and could drop the best match before it is ever scored. The cost is bounded by the
     * library, not by the index: four small integers per phrase per column per matching document.
     */
    @Query(
        "SELECT document_text_fts.rowid AS documentId, " +
            "matchinfo(document_text_fts, 'pcnalx') AS matchInfo " +
            "FROM document_text_fts " +
            "JOIN document_text ON document_text.rowid = document_text_fts.rowid " +
            "WHERE document_text_fts MATCH :match",
    )
    suspend fun candidates(match: String): List<RawContentMatch>

    /**
     * Phase two: excerpts for the best-ranked ids only, since `snippet` reads the content row.
     *
     * The marker characters are repeated in Kotlin as [SNIPPET_START] and [SNIPPET_END]; Room
     * cannot template a query string, so the two copies have to be kept in step by hand.
     */
    @Query(
        "SELECT document_text_fts.rowid AS documentId, " +
            "snippet(document_text_fts, char(1), char(2), '…', -1, 32) AS snippet " +
            "FROM document_text_fts " +
            "WHERE document_text_fts MATCH :match AND document_text_fts.rowid IN (:ids)",
    )
    suspend fun snippets(match: String, ids: List<Long>): List<SnippetRow>

    companion object {
        /** Opens a matched word in a `snippet`: `char(1)` in the query above. */
        const val SNIPPET_START = '\u0001'

        /** Closes a matched word in a `snippet`: `char(2)` in the query above. */
        const val SNIPPET_END = '\u0002'
    }
}
