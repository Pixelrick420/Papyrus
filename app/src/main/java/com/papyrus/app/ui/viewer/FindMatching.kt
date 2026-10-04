package com.papyrus.app.ui.viewer

import com.papyrus.app.viewer.OfficeBlock
import com.papyrus.app.viewer.OfficeCell

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

/**
 * One hit per match, not per chunk: a chunk holding three matches contributes its index three
 * times, in document order. The find bar steps through this list, so each match is one step and
 * the counter is the true number of matches. Counting per chunk made matches close together
 * collapse into a single step.
 */
fun findChunkHits(chunks: List<String>, query: String): List<Int> {
    if (query.isBlank()) return emptyList()
    val hits = ArrayList<Int>()
    chunks.forEachIndexed { index, chunk -> repeat(countOccurrences(chunk, query)) { hits += index } }
    return hits
}

/** Same shape as [findChunkHits]: a block's index repeats once per match rendered inside it. */
fun findBlockHits(blocks: List<OfficeBlock>, query: String): List<Int> {
    if (query.isBlank()) return emptyList()
    val hits = ArrayList<Int>()
    blocks.forEachIndexed { index, block -> repeat(countOccurrences(block, query)) { hits += index } }
    return hits
}

/**
 * The separate pieces of text a block draws, in reading order. Matches are found inside one piece,
 * never across two, because that is how they are painted: a cell's paragraphs are separate `Text`s,
 * so a match straddling them could be counted but never highlighted.
 */
internal fun officeBlockLines(block: OfficeBlock): List<String> = when (block) {
    is OfficeBlock.Heading -> listOf(block.text)
    is OfficeBlock.Paragraph -> listOf(block.text)
    is OfficeBlock.Table -> block.rows.flatMap { row -> row.flatMap(::officeCellLines) }
    is OfficeBlock.Image -> emptyList()
}

/** The extractor joins a cell's paragraphs with newlines; each one is drawn as its own `Text`. */
internal fun officeCellLines(cell: OfficeCell): List<String> = cell.text.split('\n').filter { it.isNotEmpty() }

internal fun countOccurrences(block: OfficeBlock, query: String): Int =
    officeBlockLines(block).sumOf { countOccurrences(it, query) }

internal fun countOccurrences(cell: OfficeCell, query: String): Int =
    officeCellLines(cell).sumOf { countOccurrences(it, query) }
