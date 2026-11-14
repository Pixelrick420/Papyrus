package io.github.pixelrick420.papyrus.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal class FakeSearchDao : SearchDao {

    val queue = mutableListOf<DocumentEntity>()

    // When true, [pending] behaves like the database: a document stays in the queue until a row has been written for it.
    var queueUntilWritten = false

    val pendingQueries = mutableListOf<Pair<Int, Int>>()

    val eagerPendingQueries = mutableListOf<Pair<Long, Int>>()

    var indexedRows = listOf<IndexedDocument>()
    var indexedQueries = 0

    var existingIds: Set<Long>? = null

    val written = mutableListOf<DocumentTextEntity>()

    val deleted = mutableListOf<Long>()

    var textSummaryRows = listOf<TextSummaryRow>()
    val textSummaryQueries = mutableListOf<Long>()

    var candidateRows = listOf<RawContentMatch>()
    val candidateQueries = mutableListOf<String>()

    var snippetRows = listOf<SnippetRow>()
    val snippetQueries = mutableListOf<Pair<String, List<Long>>>()

    override suspend fun pending(version: Int, limit: Int): List<DocumentEntity> {
        pendingQueries += version to limit
        return take(limit) { true }
    }

    override suspend fun pendingUpTo(version: Int, maxBytes: Long, limit: Int): List<DocumentEntity> {
        eagerPendingQueries += maxBytes to limit
        return take(limit) { it.sizeBytes <= maxBytes }
    }

    private fun take(limit: Int, include: (DocumentEntity) -> Boolean): List<DocumentEntity> {
        if (queueUntilWritten) {
            val done = written.mapTo(HashSet()) { it.documentId }
            return queue.filter { it.id !in done && include(it) }.take(limit)
        }
        val batch = queue.filter(include).take(limit)
        batch.forEach { queue.remove(it) }
        return batch
    }

    override suspend fun indexedWithModified(version: Int, afterId: Long, limit: Int): List<IndexedDocument> {
        indexedQueries++
        return indexedRows.filter { it.document.id > afterId }.sortedBy { it.document.id }.take(limit)
    }

    override suspend fun existingId(id: Long): Long? = if (existingIds?.contains(id) != false) id else null

    override suspend fun insertText(row: DocumentTextEntity) {
        written += row
    }

    override suspend fun deleteText(id: Long) {
        deleted += id
    }

    override fun observeTextSummary(id: Long): Flow<List<TextSummaryRow>> {
        textSummaryQueries += id
        return flowOf(textSummaryRows)
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
