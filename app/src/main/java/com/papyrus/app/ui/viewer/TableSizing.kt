package com.papyrus.app.ui.viewer

import com.papyrus.app.viewer.OfficeCell
import kotlin.math.max
import kotlin.math.min

/**
 * What each column of a table would like and what it can live with, in dp, cell gap and padding
 * included. [preferredDp] is the column's longest line on one line, capped so prose wraps rather than
 * making the column ever wider; [minDp] is how far it can be squeezed before a word has to break or
 * the column stops being readable.
 */
internal class ColumnMetrics(val minDp: FloatArray, val preferredDp: FloatArray)

/**
 * Sizes columns from their content, the way an HTML `table-layout: auto` table does, because a .doc
 * table arrives as text only and the OOXML/ODF column widths would need style resolution. One width
 * for every column made a serial-number column as wide as a prose one.
 *
 * Glyph widths are estimated, not measured: a text layout per cell is a layout per cell on the main
 * thread, for tables that can run to thousands of rows, and a few percent of error only shifts a
 * column edge by a few dp. [emDp] is the cell text's size in dp *including* zoom (zoom scales
 * `fontScale`), which is what makes the widths follow it. Cells spanning several columns are ignored
 * until the single-column ones have set the widths, then only widen columns that cannot hold them.
 */
internal fun measureColumns(
    rows: List<List<OfficeCell>>,
    columnCount: Int,
    emDp: Float,
    chromeDp: Float,
): ColumnMetrics {
    val lineDp = FloatArray(columnCount)
    val wordDp = FloatArray(columnCount)
    val spanning = ArrayList<SpanNeed>()
    for (row in rows) {
        for (cell in row) {
            if (cell.column !in 0 until columnCount) continue
            val extent = extentOf(cell.text)
            val line = extent.lineEm * emDp * ESTIMATE_MARGIN
            if (cell.colspan == 1) {
                lineDp[cell.column] = max(lineDp[cell.column], line)
                wordDp[cell.column] = max(wordDp[cell.column], extent.wordEm * emDp * ESTIMATE_MARGIN)
            } else {
                spanning += SpanNeed(cell.column, min(cell.lastColumn, columnCount - 1), line)
            }
        }
    }

    val floorText = MIN_TEXT_EM * emDp
    val capText = MAX_TEXT_EM * emDp
    val preferred = FloatArray(columnCount) { lineDp[it].coerceIn(floorText, capText) + chromeDp }
    // A column whose text is shorter than the squeeze floor never shrinks: wrapping "12/03/2024" or a
    // name onto two lines to save a few dp reads worse than a few dp of scrolling.
    val minimum = FloatArray(columnCount) {
        val squeezed = max(min(wordDp[it], MAX_WORD_EM * emDp), SQUEEZE_FLOOR_EM * emDp) + chromeDp
        min(preferred[it], squeezed)
    }

    // Narrowest spans first, as HTML does, so a wide merged title is judged against columns that already
    // hold their own content. The need is capped like any prose: a 300-character title row must wrap
    // across the table, not make it 1800dp wide.
    for (span in spanning.sortedBy { it.last - it.first }) {
        val columns = span.last - span.first + 1
        val need = min(span.lineDp, capText) + chromeDp
        var have = 0f
        for (c in span.first..span.last) have += preferred[c]
        if (need > have) {
            val extra = (need - have) / columns
            for (c in span.first..span.last) preferred[c] += extra
        }
    }
    return ColumnMetrics(minimum, preferred)
}

/**
 * Fits [metrics] into [availableDp]. A table that already fits is left at its preferred widths rather
 * than stretched, or a two-column table would spread a serial number across half the screen. One that
 * does not fit gives up width in proportion to how much each column has to spare, so prose columns
 * shrink and short ones stay put. If even the minimums do not fit, the minimums are returned and the
 * table scrolls horizontally.
 */
internal fun fitColumns(metrics: ColumnMetrics, availableDp: Float): FloatArray {
    val preferred = metrics.preferredDp
    val minimum = metrics.minDp
    val total = preferred.sum()
    if (!availableDp.isFinite() || total <= availableDp) return preferred.copyOf()
    val floor = minimum.sum()
    if (floor >= availableDp) return minimum.copyOf()
    // total > availableDp > floor, so the flexible part is positive.
    val share = (availableDp - floor) / (total - floor)
    return FloatArray(preferred.size) { minimum[it] + (preferred[it] - minimum[it]) * share }
}

/** Where a cell sits on a table's grid; spans are at least 1. */
internal data class GridCell(val row: Int, val column: Int, val colspan: Int, val rowspan: Int)

/**
 * Row heights for cells that would each like [natural] px (parallel to [cells]). A row is as tall as the
 * tallest cell sitting in that row alone, and never under [minRow]. A cell spanning rows is judged after
 * those, against the rows it covers together: if it is taller, the last of them grows by the shortfall,
 * so the rows above keep their own height. A span running past the last row is cut at it.
 */
internal fun rowHeights(cells: List<GridCell>, natural: IntArray, rowCount: Int, minRow: Int): IntArray {
    val heights = IntArray(rowCount) { minRow }
    cells.forEachIndexed { i, cell ->
        if (cell.rowspan == 1) heights[cell.row] = max(heights[cell.row], natural[i])
    }
    cells.forEachIndexed { i, cell ->
        if (cell.rowspan == 1) return@forEachIndexed
        val last = min(cell.row + cell.rowspan, rowCount) - 1
        var have = 0
        for (row in cell.row..last) have += heights[row]
        if (natural[i] > have) heights[last] += natural[i] - have
    }
    return heights
}

private class SpanNeed(val first: Int, val last: Int, val lineDp: Float)

private class Extent(val lineEm: Float, val wordEm: Float)

/** Widest line, and widest unbreakable run, of [text] in ems. Lines are split on `\n`, as a cell's paragraphs are. */
private fun extentOf(text: String): Extent {
    var line = 0f
    var word = 0f
    var widestLine = 0f
    var widestWord = 0f
    for (ch in text) {
        when {
            ch == '\n' -> {
                widestLine = max(widestLine, line)
                widestWord = max(widestWord, word)
                line = 0f
                word = 0f
            }
            ch.isWhitespace() -> {
                line += advanceEm(ch)
                widestWord = max(widestWord, word)
                word = 0f
            }
            else -> {
                val advance = advanceEm(ch)
                line += advance
                word += advance
                // CJK has no spaces, but a line may break after any character, so each is its own "word".
                if (isBreakEverywhere(ch)) {
                    widestWord = max(widestWord, word)
                    word = 0f
                }
            }
        }
    }
    return Extent(max(widestLine, line), max(widestWord, word))
}

private fun isBreakEverywhere(ch: Char): Boolean = ch.code >= WIDE_FROM && !ch.isSurrogate()

/**
 * Approximate advance of [ch] in ems for Roboto, which is what the theme uses. The tracking term is
 * Material's 0.4sp on 12sp body-small text; without it every line would come out about 3% short.
 */
private fun advanceEm(ch: Char): Float = TRACKING_EM + when {
    ch in '0'..'9' -> 0.56f
    ch == ' ' -> 0.25f
    ch == '\t' -> 1f
    ch in 'a'..'z' -> when (ch) {
        'i', 'j', 'l' -> 0.25f
        'f', 'r', 't' -> 0.35f
        'm', 'w' -> 0.85f
        else -> 0.54f
    }
    ch in 'A'..'Z' -> when (ch) {
        'I' -> 0.28f
        'M', 'W' -> 0.88f
        else -> 0.64f
    }
    ch.code < ASCII_END -> when (ch) {
        '.', ',', ':', ';', '\'', '!', '|' -> 0.27f
        else -> 0.52f
    }
    // Indic scripts mix wide base letters with zero-width vowel signs; this is the average per code point.
    ch.code in INDIC_START..INDIC_END -> 0.75f
    // Half of a surrogate pair: an emoji is two chars and roughly one em.
    ch.isSurrogate() -> 0.5f
    ch.code >= WIDE_FROM -> 1f
    // Accented Latin, Cyrillic, Greek and the rest.
    else -> 0.58f
}

/** A column is never narrower than this much text: wide enough for a serial number or a short code. */
private const val MIN_TEXT_EM = 3f

/** Prose wraps past this many ems (about 260dp of 12sp text) instead of widening its column further. */
private const val MAX_TEXT_EM = 22f

/** A column with prose in it is squeezed to this many ems at the least, unless one word is wider. */
private const val SQUEEZE_FLOOR_EM = 8f

/** A single very long word (a URL) must not set the minimum: it breaks mid-word instead. */
private const val MAX_WORD_EM = 12f

private const val TRACKING_EM = 0.034f

/** Underestimating wraps a short value onto a second line for want of a few dp; overestimating costs only a few dp. */
private const val ESTIMATE_MARGIN = 1.04f

private const val ASCII_END = 0x80
private const val INDIC_START = 0x0900
private const val INDIC_END = 0x0DFF

/** From here up: CJK, Hangul, fullwidth forms. */
private const val WIDE_FROM = 0x2E80
