package com.papyrus.app.ui.viewer

import com.papyrus.app.viewer.OfficeCell
import kotlin.math.max
import kotlin.math.min

internal class ColumnMetrics(val minDp: FloatArray, val preferredDp: FloatArray)

// Estimated, not measured: per-cell layout is too costly for large tables (a .doc table arrives as text only).
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

    // Narrowest spans first, as HTML does, so a wide merged title is judged against columns that
    // already hold their own content. The need is capped like any prose, so a long title wraps.
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

internal fun fitColumns(metrics: ColumnMetrics, availableDp: Float): FloatArray {
    val preferred = metrics.preferredDp
    val minimum = metrics.minDp
    val total = preferred.sum()
    if (!availableDp.isFinite() || total <= availableDp) return preferred.copyOf()
    val floor = minimum.sum()
    if (floor >= availableDp) return minimum.copyOf()
    val share = (availableDp - floor) / (total - floor)
    return FloatArray(preferred.size) { minimum[it] + (preferred[it] - minimum[it]) * share }
}

internal data class GridCell(val row: Int, val column: Int, val colspan: Int, val rowspan: Int)

// A rowspan grows its last row by any shortfall.
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
                // CJK has no spaces, but breaks after any character, so each is its own word.
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

// Approximate advance of [ch] in ems for Roboto, the theme's font. The tracking term is Material's
// 0.4sp on 12sp body-small text; without it every line comes out about 3% short.
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
    // Indic scripts mix wide base letters with zero-width vowel signs; average per code point.
    ch.code in INDIC_START..INDIC_END -> 0.75f
    ch.isSurrogate() -> 0.5f
    ch.code >= WIDE_FROM -> 1f
    else -> 0.58f
}

internal const val TABLE_BAND_ROWS = 30

// Extended so no rowspan crosses a boundary: each band's layout stays identical to the same rows in a whole-table layout. A span taller than [maxRows] makes one band.
internal fun bandBoundaries(rows: List<List<OfficeCell>>, maxRows: Int): List<IntRange> {
    val count = rows.size
    if (count == 0) return emptyList()
    val width = maxRows.coerceAtLeast(1)
    val bands = ArrayList<IntRange>()
    var start = 0
    var end = width.coerceAtMost(count)
    var reach = 0
    for (rowIndex in 0 until count) {
        for (cell in rows[rowIndex]) {
            reach = max(reach, (rowIndex + cell.rowspan.coerceAtLeast(1)).coerceAtMost(count))
        }
        if (rowIndex + 1 >= end) {
            end = max(end, reach)
            if (rowIndex + 1 >= end) {
                bands += start until end
                start = end
                end = min(start + width, count)
                reach = 0
            }
        }
    }
    return bands
}

private const val MIN_TEXT_EM = 3f

private const val MAX_TEXT_EM = 22f

private const val SQUEEZE_FLOOR_EM = 8f

/** A single very long word (a URL) must not set the minimum: it breaks mid-word instead. */
private const val MAX_WORD_EM = 12f

private const val TRACKING_EM = 0.034f

/** Underestimating wraps a short value for a few dp; overestimating costs a few dp. */
private const val ESTIMATE_MARGIN = 1.04f

private const val ASCII_END = 0x80
private const val INDIC_START = 0x0900
private const val INDIC_END = 0x0DFF

private const val WIDE_FROM = 0x2E80
