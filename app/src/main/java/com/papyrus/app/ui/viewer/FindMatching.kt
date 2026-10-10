package com.papyrus.app.ui.viewer

import com.papyrus.app.viewer.LineText
import com.papyrus.app.viewer.OfficeBlock
import com.papyrus.app.viewer.OfficeCell
import com.papyrus.app.viewer.splitLines

/*
 * Matching is `indexOf(ignoreCase = true)`, never `lowercase()` on both sides: lower-casing can
 * change a string's length and shift every later match offset.
 */

/**
 * Advances past the whole match, so "aa" in "aaaa" is two hits, not three -- the editor convention,
 * and the only one where the visible highlights match the count the find bar shows.
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
 * One hit per match, not per chunk, so the find bar steps each match once and the counter is exact.
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
 * The separate pieces of text a block draws, in reading order; matches are found inside one piece,
 * never across two, because that is how they are painted.
 */
internal fun officeBlockLines(block: OfficeBlock): List<String> = when (block) {
    is OfficeBlock.Heading -> listOf(block.text)
    is OfficeBlock.Paragraph -> listOf(block.text)
    // The marker is drawn beside the text, not in it, so it is not searchable: matches stay on the document's words.
    is OfficeBlock.ListItem -> listOf(block.text)
    is OfficeBlock.Note -> listOf(block.text)
    is OfficeBlock.Table -> block.rows.flatMap { row -> row.flatMap(::officeCellLines) }
    is OfficeBlock.Image -> emptyList()
    is OfficeBlock.Divider -> emptyList()
}

/** The extractor joins a cell's paragraphs with newlines; each one is drawn as its own `Text`. */
internal fun officeCellLines(cell: OfficeCell): List<String> = cell.text.split('\n').filter { it.isNotEmpty() }

/** [officeCellLines] with each line's formatting spans, which is what a cell actually draws. */
internal fun officeCellRichLines(cell: OfficeCell): List<LineText> = splitLines(cell.text, cell.spans)

internal fun countOccurrences(block: OfficeBlock, query: String): Int =
    officeBlockLines(block).sumOf { countOccurrences(it, query) }

internal fun countOccurrences(cell: OfficeCell, query: String): Int =
    officeCellLines(cell).sumOf { countOccurrences(it, query) }
