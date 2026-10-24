package com.papyrus.app.data

/**
 * A [SearchDao] with no database behind it: the queue and the two read phases are lists the test
 * fills in, and every call is recorded so a test can assert what the indexer or the repository
 * asked for.
 *
 * The interface's signatures use no Android type, which is what makes this possible under plain
 * JUnit: the real SQL is a separate concern, covered only by running the app.
 */
internal class FakeSearchDao : SearchDao {

    /** What [pending] will answer with, in order; each call takes up to its `limit` off the front. */
    val queue = mutableListOf<DocumentEntity>()

    /** Every `(version, limit)` pair [pending] was asked for, in order. */
    val pendingQueries = mutableListOf<Pair<Int, Int>>()

    /** Ids [existingId] reported as still in the library; null means every id is. */
    var existingIds: Set<Long>? = null

    /** Rows written through [insertText], which [replace] also goes through. */
    val written = mutableListOf<DocumentTextEntity>()

    /** Ids deleted with [deleteText], the delete half of [replace]. */
    val deleted = mutableListOf<Long>()

    /** What [candidates] answers with, and the MATCH expressions it was asked. */
    var candidateRows = listOf<RawContentMatch>()
    val candidateQueries = mutableListOf<String>()

    /** What [snippets] answers with, filtered to the ids asked for; the queries are recorded too. */
    var snippetRows = listOf<SnippetRow>()
    val snippetQueries = mutableListOf<Pair<String, List<Long>>>()

    override suspend fun pending(version: Int, limit: Int): List<DocumentEntity> {
        pendingQueries += version to limit
        val batch = queue.take(limit)
        repeat(batch.size) { queue.removeAt(0) }
        return batch
    }

    override suspend fun existingId(id: Long): Long? = if (existingIds?.contains(id) != false) id else null

    override suspend fun insertText(row: DocumentTextEntity) {
        written += row
    }

    override suspend fun deleteText(id: Long) {
        deleted += id
    }

    override suspend fun candidates(match: String): List<RawContentMatch> {
        candidateQueries += match
        return candidateRows
    }

    override suspend fun snippets(match: String, ids: List<Long>): List<SnippetRow> {
        snippetQueries += match to ids
        return snippetRows.filter { it.documentId in ids }
    }
}
