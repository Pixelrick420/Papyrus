package io.github.pixelrick420.papyrus.viewer.pptx.text

/** The room one paragraph's lines get: the frame width already net of its insets and margins. */
internal class FillMetrics(
    val widthPt: Double,
    val firstLineIndentPt: Double = 0.0,
    val rtl: Boolean = false,
    val wrap: TextWrap = TextWrap.SQUARE,
    val tabs: TabStops = TabStops.DEFAULT,
)

/** One line the filler chose: [start] and [end] are offsets into the paragraph's text. */
internal class FilledLine(
    val start: Int,
    val end: Int,
    val startPt: Double,
    val widthPt: Double,
    val rtl: Boolean,
)

/**
 * Greedy line filling (plan §4.7 step 5): break at the last opportunity that fits, split a word that
 * is wider than the line with [TextMeasurer.offsetForAdvance], and never wrap when `wrap="none"`.
 */
internal class LineFiller(private val measurer: TextMeasurer) {

    fun fill(
        text: CharSequence,
        segments: List<ShapedRun>,
        breaks: List<Int>,
        breakBefore: BooleanArray,
        metrics: FillMetrics,
    ): List<FilledLine> {
        val lines = ArrayList<FilledLine>()
        var chunkStart = 0
        for (chunkEnd in breaks + text.length) {
            if (chunkEnd < chunkStart) continue
            lines += fillChunk(text, segments, chunkStart, chunkEnd, breakBefore, metrics)
            chunkStart = chunkEnd
        }
        return lines
    }

    private fun fillChunk(
        text: CharSequence,
        segments: List<ShapedRun>,
        chunkStart: Int,
        chunkEnd: Int,
        breakBefore: BooleanArray,
        metrics: FillMetrics,
    ): List<FilledLine> {
        val indent = if (chunkStart == 0) metrics.firstLineIndentPt else 0.0
        if (chunkStart == chunkEnd) {
            return listOf(FilledLine(chunkStart, chunkEnd, indent, 0.0, metrics.rtl))
        }
        if (metrics.wrap == TextWrap.NONE) {
            val prefix = prefixAdvance(text, segments, chunkStart, chunkEnd, indent, metrics.tabs)
            return listOf(
                FilledLine(
                    chunkStart,
                    chunkEnd,
                    indent,
                    widthUpTo(text, prefix, chunkStart, chunkEnd),
                    metrics.rtl,
                ),
            )
        }
        val lines = ArrayList<FilledLine>()
        var start = chunkStart
        var first = chunkStart == 0
        while (start < chunkEnd) {
            val lineIndent = if (first) metrics.firstLineIndentPt else 0.0
            val available = (metrics.widthPt - lineIndent).coerceAtLeast(0.0)
            val prefix = prefixAdvance(text, segments, start, chunkEnd, lineIndent, metrics.tabs)
            var end = -1
            for (offset in start + 1..chunkEnd) {
                if (offset != chunkEnd && !breakBefore.getOrElse(offset) { false }) continue
                if (widthUpTo(text, prefix, start, offset) > available) break
                end = offset
            }
            val chosen = if (end > start) {
                end
            } else {
                splitAt(text, segments, start, chunkEnd, available, lineIndent, metrics.tabs)
            }
            lines += FilledLine(
                start,
                chosen,
                lineIndent,
                widthUpTo(text, prefix, start, chosen),
                metrics.rtl,
            )
            start = chosen
            first = false
        }
        return lines
    }

    /** The furthest offset that still fits, or the next one when a single character does not. */
    private fun splitAt(
        text: CharSequence,
        segments: List<ShapedRun>,
        start: Int,
        end: Int,
        available: Double,
        indentPt: Double,
        tabs: TabStops,
    ): Int {
        val segment = segments.firstOrNull { start >= it.start && start < it.end }
            ?: return start + 1
        val limit = minOf(segment.end, end)
        var offset = measurer.offsetForAdvance(
            segment.font,
            segment.sizePt,
            text,
            start,
            limit,
            segment.rtl,
            segment.features,
            segment.letterSpacingEm,
            available.toFloat(),
        )
        offset = offset.coerceIn(start + 1, end)
        while (offset > start + 1 &&
            advanceRange(text, segments, start, offset, indentPt, tabs) > available
        ) {
            offset--
        }
        return offset
    }

    private fun prefixAdvance(
        text: CharSequence,
        segments: List<ShapedRun>,
        start: Int,
        end: Int,
        indentPt: Double,
        tabs: TabStops,
    ): DoubleArray {
        val prefix = DoubleArray(end - start + 1)
        for (offset in start until end) {
            prefix[offset - start + 1] = advanceRange(text, segments, start, offset + 1, indentPt, tabs)
        }
        return prefix
    }

    private fun advanceRange(
        text: CharSequence,
        segments: List<ShapedRun>,
        start: Int,
        end: Int,
        indentPt: Double,
        tabs: TabStops,
    ): Double {
        if (!hasTab(text, start, end)) return measuredAdvance(text, segments, start, end)
        var total = 0.0
        var offset = start
        while (offset < end) {
            if (text[offset] == '\t') {
                val xPt = indentPt + total
                total += tabs.stopAt(xPt).positionPt - xPt
                offset++
            } else {
                val segment = segments.firstOrNull { offset >= it.start && offset < it.end } ?: break
                val to = minOf(segment.end, end)
                total += measurer.advance(
                    segment.font,
                    segment.sizePt,
                    text,
                    offset,
                    to,
                    segment.rtl,
                    segment.features,
                    segment.letterSpacingEm,
                )
                offset = to
            }
        }
        return total
    }

    private fun measuredAdvance(
        text: CharSequence,
        segments: List<ShapedRun>,
        start: Int,
        end: Int,
    ): Double {
        var total = 0.0
        for (segment in segments) {
            val from = maxOf(segment.start, start)
            val to = minOf(segment.end, end)
            if (to <= from) continue
            total += measurer.advance(
                segment.font,
                segment.sizePt,
                text,
                from,
                to,
                segment.rtl,
                segment.features,
                segment.letterSpacingEm,
            )
        }
        return total
    }

    private fun hasTab(text: CharSequence, start: Int, end: Int): Boolean {
        for (offset in start until end) if (text[offset] == '\t') return true
        return false
    }

    private fun widthUpTo(text: CharSequence, prefix: DoubleArray, start: Int, end: Int): Double =
        prefix[trimmedEnd(text, start, end) - start]

    private fun trimmedEnd(text: CharSequence, start: Int, end: Int): Int {
        var trimmed = end
        while (trimmed > start && isTrailingSpace(text[trimmed - 1])) trimmed--
        return trimmed
    }

    private fun isTrailingSpace(char: Char): Boolean = char == ' ' || char == '\t'
}
