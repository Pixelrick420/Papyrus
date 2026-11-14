package io.github.pixelrick420.papyrus.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ContentHit(
    val documentId: Long,
    val snippet: String,
    val highlightRanges: List<IntRange>,
    val score: Double,
)

// Phase one asks the index which documents match using `matchinfo` only; phase two fetches excerpts for the survivors, since `snippet` reads the content row.
class SearchRepository(private val dao: SearchDao) {

    // Never returns a hit whose excerpt went missing: a result that cannot show why it matched is worse than none.
    suspend fun search(input: String, limit: Int = DEFAULT_LIMIT): List<ContentHit> =
        withContext(Dispatchers.IO) {
            val query = SearchQuery.parse(input) ?: return@withContext emptyList()
            val ranked = dao.candidates(query.expression)
                .map { RawScore(it.documentId, Bm25Ranker.score(it.matchInfo)) }
                .sortedWith(compareByDescending<RawScore> { it.score }.thenBy { it.documentId })
                .take(limit.coerceAtLeast(0))
            if (ranked.isEmpty()) return@withContext emptyList()
            val excerpts = dao.snippets(query.expression, ranked.map { it.documentId })
                .associateBy { it.documentId }
            ranked.mapNotNull { scored ->
                val row = excerpts[scored.documentId] ?: return@mapNotNull null
                val excerpt = withMarkersRemoved(row.snippet.orEmpty())
                ContentHit(
                    documentId = scored.documentId,
                    snippet = excerpt.text,
                    highlightRanges = excerpt.highlightRanges,
                    score = scored.score,
                )
            }
        }

    private data class RawScore(val documentId: Long, val score: Double)

    private class Excerpt(val text: String, val highlightRanges: List<IntRange>)

    // An unterminated marker (the excerpt was cut short) highlights the rest of the text.
    private fun withMarkersRemoved(raw: String): Excerpt {
        val text = StringBuilder()
        val ranges = mutableListOf<IntRange>()
        var highlightedFrom: Int? = null
        for (character in raw) {
            when (character) {
                SearchDao.SNIPPET_START -> highlightedFrom = text.length
                SearchDao.SNIPPET_END -> {
                    highlightedFrom?.let { from -> ranges.addRange(text.length, from) }
                    highlightedFrom = null
                }
                else -> text.append(character)
            }
        }
        highlightedFrom?.let { from -> ranges.addRange(text.length, from) }
        return Excerpt(text.toString(), ranges)
    }

    private fun MutableList<IntRange>.addRange(to: Int, from: Int) {
        if (to > from) add(from until to)
    }

    companion object {
        const val DEFAULT_LIMIT = 20
    }
}
