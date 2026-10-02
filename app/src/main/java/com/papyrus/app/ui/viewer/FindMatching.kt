package com.papyrus.app.ui.viewer

/*
 * Matching is `indexOf(ignoreCase = true)`, never `lowercase()` on both sides: lower-casing can
 * change a string's length (Turkish dotted capital I becomes two chars), which shifts every later
 * match offset and paints highlights on the wrong characters.
 */

/**
 * Advances past the whole match, so "aa" in "aaaa" is two hits, not three. That is the editor
 * convention, and the only one where the visible highlights match the count the find bar shows.
 */
fun findMatchRanges(text: String, query: String): List<IntRange> {
    if (query.isEmpty() || text.length < query.length) return emptyList()
    val ranges = ArrayList<IntRange>()
    var from = 0
    while (true) {
        val at = text.indexOf(query, from, ignoreCase = true)
        if (at < 0) return ranges
        ranges += at until at + query.length
        from = at + query.length
    }
}

/** For Markdown, which has no chunks to jump between. Follows [findMatchRanges] so both agree. */
fun countOccurrences(text: String, query: String): Int {
    if (query.isEmpty()) return 0
    var count = 0
    var from = 0
    while (true) {
        val at = text.indexOf(query, from, ignoreCase = true)
        if (at < 0) return count
        count++
        from = at + query.length
    }
}

fun findChunkHits(chunks: List<String>, query: String): List<Int> {
    if (query.isBlank()) return emptyList()
    return chunks.mapIndexedNotNull { index, chunk -> index.takeIf { chunk.contains(query, ignoreCase = true) } }
}
