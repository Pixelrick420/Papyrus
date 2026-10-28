package com.papyrus.app.ui.viewer

import android.text.Spanned
import android.text.style.URLSpan
import com.papyrus.app.viewer.LineText
import com.papyrus.app.viewer.MarkdownFootnotes
import com.papyrus.app.viewer.OfficeBlock
import com.papyrus.app.viewer.OfficeCell
import com.papyrus.app.viewer.TextSpan
import com.papyrus.app.viewer.splitLines
import io.noties.markwon.core.spans.LinkSpan
import io.noties.markwon.image.AsyncDrawableSpan

// Match with indexOf(ignoreCase = true), never lowercase() both sides: lower-casing can
// change a string's length and shift every later match offset.

/**
 * Advances past the whole match, so "aa" in "aaaa" is two hits, not three -- the editor convention,
 * and the only one where the visible highlights match the count the find bar shows.
 *
 * If [text] is [Spanned] (e.g. rendered Markdown), spans with unrendered matching text (such as links
 * with target URLs or images/formulas with source destinations) whose rendered counterpart exists are
 * also included when no direct match already covers them.
 */
fun findMatchRanges(text: CharSequence, query: String): List<IntRange> {
    if (query.isEmpty()) return emptyList()
    val str = text.toString()
    val direct = ArrayList<IntRange>()
    if (str.length >= query.length) {
        var from = 0
        while (true) {
            val at = str.indexOf(query, from, ignoreCase = true)
            if (at < 0) break
            direct += at until at + query.length
            from = at + query.length
        }
    }
    if (text !is Spanned) return direct

    val spanRanges = findHiddenSpanMatches(text, query, direct)
    if (spanRanges.isEmpty()) return direct

    return (direct + spanRanges).sortedWith(compareBy<IntRange> { it.first }.thenBy { it.last })
}

/** Matches in text with explicit [TextSpan] formatting (e.g. Office documents). */
fun findMatchRanges(text: String, spans: List<TextSpan>, query: String): List<IntRange> {
    val direct = findMatchRanges(text, query)
    if (spans.isEmpty() || query.isEmpty()) return direct
    val spanMatches = ArrayList<IntRange>()
    for (span in spans) {
        val link = span.format.link ?: continue
        if (!link.contains(query, ignoreCase = true)) continue
        val start = span.start.coerceIn(0, text.length)
        val end = span.end.coerceIn(start, text.length)
        if (end <= start) continue
        val range = start until end
        val alreadyCovered = direct.any { it.first < end && it.last >= start }
        if (!alreadyCovered && !spanMatches.any { it.first == range.first && it.last == range.last }) {
            spanMatches += range
        }
    }
    if (spanMatches.isEmpty()) return direct
    return (direct + spanMatches).sortedWith(compareBy<IntRange> { it.first }.thenBy { it.last })
}

private fun findHiddenSpanMatches(
    spanned: Spanned,
    query: String,
    directMatches: List<IntRange>,
): List<IntRange> {
    val results = ArrayList<IntRange>()
    val spans = spanned.getSpans(0, spanned.length, Any::class.java)
    for (span in spans) {
        val hiddenText = extractSpanHiddenText(span) ?: continue
        if (!hiddenText.contains(query, ignoreCase = true)) continue
        val start = spanned.getSpanStart(span)
        val end = spanned.getSpanEnd(span)
        if (end <= start) continue
        val range = start until end
        val alreadyCovered = directMatches.any { it.first < end && it.last >= start }
        if (!alreadyCovered && !results.any { it.first == range.first && it.last == range.last }) {
            results += range
        }
    }
    return results
}

internal fun extractSpanHiddenText(span: Any): String? {
    // A footnote's anchor is made up by the preprocessor, not written by the author: searching for "note" must not
    // light up every marker whose label was `[^note]`.
    if (span is LinkSpan) return span.link.takeUnless { MarkdownFootnotes.isAnchor(it) }
    if (span is AsyncDrawableSpan) return span.drawable?.destination
    if (span is URLSpan) {
        return try {
            span.url
        } catch (_: RuntimeException) {
            null
        }
    }
    return null
}

/** For Markdown, which has no chunks to jump between. Follows [findMatchRanges] so both agree. */
fun countOccurrences(text: CharSequence, query: String): Int {
    if (query.isEmpty()) return 0
    if (text is Spanned) return findMatchRanges(text, query).size
    var count = 0
    var from = 0
    val str = text.toString()
    while (true) {
        val at = str.indexOf(query, from, ignoreCase = true)
        if (at < 0) return count
        count++
        from = at + query.length
    }
}

internal fun countOccurrences(text: String, spans: List<TextSpan>, query: String): Int =
    findMatchRanges(text, spans, query).size

/**
 * One hit per match, not per chunk, so the find bar steps each match once. [limit] caps the list
 * (a short query in a 32 MB file matches millions of times); [shouldStop] is polled per chunk.
 */
fun findChunkHits(
    chunks: List<String>,
    query: String,
    limit: Int = Int.MAX_VALUE,
    shouldStop: () -> Boolean = { false },
): List<Int> {
    if (query.isBlank()) return emptyList()
    val hits = ArrayList<Int>()
    for (index in chunks.indices) {
        if (hits.size >= limit || shouldStop()) break
        repeat(minOf(countOccurrences(chunks[index], query), limit - hits.size)) { hits += index }
    }
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
    // The marker is drawn beside the text, not in it, so it is not searchable.
    is OfficeBlock.ListItem -> listOf(block.text)
    is OfficeBlock.Note -> listOf(block.text)
    is OfficeBlock.Table -> block.rows.flatMap { row -> row.flatMap(::officeCellLines) }
    is OfficeBlock.Image -> emptyList()
    OfficeBlock.Divider -> emptyList()
}

/** The extractor joins a cell's paragraphs with newlines; each one is drawn as its own `Text`. */
internal fun officeCellLines(cell: OfficeCell): List<String> = cell.text.split('\n').filter { it.isNotEmpty() }

/** [officeCellLines] with each line's formatting spans, which is what a cell actually draws. */
internal fun officeCellRichLines(cell: OfficeCell): List<LineText> = splitLines(cell.text, cell.spans)

internal fun countOccurrences(block: OfficeBlock, query: String): Int = when (block) {
    is OfficeBlock.Heading -> countOccurrences(block.text, block.spans, query)
    is OfficeBlock.Paragraph -> countOccurrences(block.text, block.spans, query)
    is OfficeBlock.ListItem -> countOccurrences(block.text, block.spans, query)
    is OfficeBlock.Note -> countOccurrences(block.text, block.spans, query)
    is OfficeBlock.Table -> block.rows.sumOf { row -> row.sumOf { cell -> countOccurrences(cell, query) } }
    is OfficeBlock.Image -> 0
    OfficeBlock.Divider -> 0
}

internal fun countOccurrences(cell: OfficeCell, query: String): Int =
    cell.text.split('\n').filter { it.isNotEmpty() }.sumOf { countOccurrences(it, cell.spans, query) }
