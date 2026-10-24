package com.papyrus.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One document whose text contains the searched words, with the excerpt to show for it. */
data class ContentHit(
    val documentId: Long,
    /** The excerpt, with [SearchDao.SNIPPET_START] and [SearchDao.SNIPPET_END] removed. */
    val snippet: String,
    /** The parts of [snippet] that matched, as ranges to draw emphasised. */
    val highlightRanges: List<IntRange>,
    /**
     * Where the earliest match sits in the indexed text, as a byte offset (SQLite counts bytes, not
     * characters), or null when `offsets()` reported none.
     *
     * This is the seam for opening a result at the match rather than at the top: re-extract the
     * document, walk this many bytes into the text, and the page or block is the one holding that
     * character — no stored page map, and nothing to re-derive at index time.
     */
    val matchOffset: Int?,
    /** BM25, higher is better. Only comparable between hits of the same search. */
    val score: Double,
)

/**
 * Content search across the library: the two-phase query, ranked, with an excerpt for each hit.
 *
 * Phase one asks the index which documents match and for their `matchinfo` only — no document text
 * is read, so ranking a large result set stays cheap. Phase two asks for excerpts, for the handful
 * of documents that survived ranking, because `snippet` reads the content row.
 *
 * Deliberately narrower than a general search service: it takes the reader's text rather than a
 * MATCH expression, so no caller has to know FTS4 syntax or its escaping rules. Tapping a hit is
 * the caller's business; all that needs carrying for it is [ContentHit.matchOffset].
 */
class SearchRepository(private val dao: SearchDao) {

    /**
     * The best [limit] documents containing [input], best first, or an empty list when [input] holds
     * nothing searchable. Never returns a hit whose excerpt went missing: a result that cannot show
     * why it matched is worse than no result.
     */
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
                    matchOffset = FtsOffsets.earliest(row.termOffsets),
                    score = scored.score,
                )
            }
        }

    /** One candidate's rank before the order is fixed. */
    private data class RawScore(val documentId: Long, val score: Double)

    /** An excerpt ready for display: the words, and which parts of them matched. */
    private class Excerpt(val text: String, val highlightRanges: List<IntRange>)

    /**
     * Strips the markers `snippet` wrapped around the matches, recording the ranges they spanned.
     * An unterminated marker — the excerpt was cut short — highlights the rest of the text.
     */
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

    /** Adds [from] until [to], skipping the empty range adjacent markers can produce. */
    private fun MutableList<IntRange>.addRange(to: Int, from: Int) {
        if (to > from) add(from until to)
    }

    companion object {
        /** Hits shown for one search; enough to choose from, few enough to read at a glance. */
        const val DEFAULT_LIMIT = 20
    }
}
