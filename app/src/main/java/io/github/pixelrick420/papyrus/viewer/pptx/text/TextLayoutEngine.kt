package io.github.pixelrick420.papyrus.viewer.pptx.text

import java.text.Bidi as JavaBidi
import java.util.Locale

/**
 * The layout pass (plan §4.7 steps 7 to 10): each paragraph is flattened, shaped, broken and filled,
 * then aligned, tabbed and turned into positioned runs and per-character boxes. Every length here is
 * in points.
 */
internal class TextLayoutEngine(
    private val measurer: TextMeasurer,
    private val shaper: TextShaper = TextShaper(),
    private val breaker: LineBreaker = LineBreaker(),
    private val lineMetrics: LineMetricsModel = LineMetricsModel.DEFAULT,
    private val bullets: BulletResolver = BulletResolver(),
) {

    private val filler = LineFiller(measurer)

    fun layout(body: TextBody, frameWidthEmu: Double): TextFrameLayout {
        val insets = body.properties.insets
        val widthPt = (frameWidthEmu - insets.left - insets.right).coerceAtLeast(0.0) / EMU_PER_POINT
        val autofit = AutofitScale.of(body.properties.autofit)
        val layouts = bullets.layouts(body, autofit)
        val lines = ArrayList<TextLine>()
        var heightPt = 0.0
        body.paragraphs.forEachIndexed { index, paragraph ->
            heightPt = layoutParagraph(
                paragraph = paragraph,
                index = index,
                bullet = layouts[index],
                widthPt = widthPt,
                body = body.properties,
                autofit = autofit,
                topPt = heightPt,
                lines = lines,
            )
        }
        return TextFrameLayout(lines, frameWidthEmu / EMU_PER_POINT, heightPt)
    }

    private fun layoutParagraph(
        paragraph: TextParagraph,
        index: Int,
        bullet: BulletLayout,
        widthPt: Double,
        body: TextBodyProperties,
        autofit: AutofitScale,
        topPt: Double,
        lines: MutableList<TextLine>,
    ): Double {
        val text = shaper.flatten(paragraph)
        val runs = shaper.shape(text, paragraph.properties.rightToLeft, autofit, measurer::hasGlyph)
        val metrics = bullet.metrics
        val textLeftPt = metrics.textLeftEmu / EMU_PER_POINT
        val paragraphWidthPt = widthPt - textLeftPt -
            (paragraph.properties.marginRightEmu ?: 0.0) / EMU_PER_POINT
        val tabs = tabStopsOf(paragraph.properties)
        val filled = filler.fill(
            text.text,
            runs,
            text.breaks,
            breaker.breakBefore(text.text, paragraph.properties, localeOf(paragraph)),
            FillMetrics(
                widthPt = paragraphWidthPt,
                firstLineIndentPt = (paragraph.properties.indentEmu ?: 0.0) / EMU_PER_POINT,
                rtl = paragraph.properties.rightToLeft,
                wrap = body.wrap,
                tabs = tabs,
            ),
        )
        val alignment = alignmentOf(paragraph.properties)
        var top = topPt
        filled.forEachIndexed { lineIndex, line ->
            val capacityPt = (paragraphWidthPt - line.startPt).coerceAtLeast(0.0)
            val spaces = spacesOn(text.text, line.start, line.end)
            val stretch = stretches(alignment, lineIndex == filled.lastIndex, text.breaks.contains(line.end))
            val paddingPt = if (stretch && spaces > 0) {
                ((capacityPt - line.widthPt) / spaces).coerceAtLeast(0.0)
            } else {
                0.0
            }
            val contentPt = line.widthPt + paddingPt * spaces
            val offsetPt = when (alignment) {
                TextAlignment.RIGHT -> (capacityPt - contentPt).coerceAtLeast(0.0)
                TextAlignment.CENTER -> ((capacityPt - contentPt) / 2.0).coerceAtLeast(0.0)
                else -> 0.0
            }
            val sizePt = largestSizePt(runs, line.start, line.end, autofit.size(paragraph.endProperties.sizePt))
            val font = fontOnLine(runs, line.start, line.end)
            val box = lineMetrics.line(
                largestSizePt = sizePt,
                spacing = autofit.lineSpacing(paragraph.properties.lineSpacing),
                fontAlignment = paragraph.properties.fontAlignment,
                font = font?.let { measurer.metrics(it, sizePt.toFloat()).emBox(sizePt) }
                    ?: FontEmBox.FALLBACK,
            )
            if (lineIndex == 0) {
                top += lineMetrics.spaceBefore(sizePt, paragraph.properties.spaceBefore)
            }
            lines += positioned(
                text = text,
                runs = runs,
                line = line,
                index = lines.size,
                paragraph = index,
                textLeftPt = textLeftPt,
                startPt = line.startPt + offsetPt,
                topPt = top,
                heightPt = box.heightPt,
                baselinePt = top + box.baselinePt,
                paddingPt = paddingPt,
                tabs = tabs,
                bullet = if (lineIndex == 0) bullet.marker else BulletMarker.None,
                markerLeftPt = metrics.markerLeftEmu / EMU_PER_POINT,
                markerRightPt = metrics.markerRightEmu / EMU_PER_POINT,
            )
            top += box.heightPt
        }
        return top + lineMetrics.spaceAfter(
            filled.lastOrNull()?.let {
                largestSizePt(runs, it.start, it.end, autofit.size(paragraph.endProperties.sizePt))
            } ?: autofit.size(paragraph.endProperties.sizePt),
            paragraph.properties.spaceAfter,
        )
    }

    private fun positioned(
        text: ParagraphText,
        runs: List<ShapedRun>,
        line: FilledLine,
        index: Int,
        paragraph: Int,
        textLeftPt: Double,
        startPt: Double,
        topPt: Double,
        heightPt: Double,
        baselinePt: Double,
        paddingPt: Double,
        tabs: TabStops,
        bullet: BulletMarker,
        markerLeftPt: Double,
        markerRightPt: Double,
    ): TextLine {
        val cells = cellsOf(runs, text.text, line, startPt, paddingPt, tabs)
        val pieces = piecesOf(text.text, runs, line)
        val order = Array(pieces.size) { it }
        if (pieces.size > 1) {
            JavaBidi.reorderVisually(
                ByteArray(pieces.size) { pieces[it].run.level.toByte() },
                0,
                order,
                0,
                pieces.size,
            )
        }
        val glyphRuns = ArrayList<GlyphRun>(pieces.size)
        for (position in order) {
            val piece = pieces[position]
            val owned = cells.filter { it.start >= piece.start && it.end <= piece.end && !it.tab }
            if (owned.isEmpty()) continue
            glyphRuns += GlyphRun(
                start = piece.start,
                end = piece.end,
                leftPt = textLeftPt + owned.minOf { it.leftPt },
                rightPt = textLeftPt + owned.maxOf { it.rightPt },
                baselinePt = baselinePt,
                font = piece.run.font,
                sizePt = piece.run.sizePt.toDouble(),
                properties = piece.run.properties,
                rtl = piece.run.rtl,
            )
        }
        val characters = cells.map {
            CharacterBox(it.start, it.end, textLeftPt + it.leftPt, textLeftPt + it.rightPt, topPt, topPt + heightPt)
        }
        val visibleEnd = trimmedEnd(text.text, line.start, line.end)
        val contentRight = cells.filter { !it.tab && it.start < visibleEnd }.maxOfOrNull { it.rightPt }
        return TextLine(
            index = index,
            paragraph = paragraph,
            start = line.start,
            end = line.end,
            leftPt = textLeftPt + startPt,
            topPt = topPt,
            widthPt = ((contentRight ?: startPt) - startPt).coerceAtLeast(0.0),
            heightPt = heightPt,
            baselinePt = baselinePt,
            runs = glyphRuns,
            characters = characters,
            bullet = bullet,
            markerLeftPt = markerLeftPt,
            markerRightPt = markerRightPt,
        )
    }

    private fun cellsOf(
        runs: List<ShapedRun>,
        text: CharSequence,
        line: FilledLine,
        startPt: Double,
        paddingPt: Double,
        tabs: TabStops,
    ): List<Cell> {
        val visibleEnd = trimmedEnd(text, line.start, line.end)
        val cells = ArrayList<Cell>()
        var segmentStart = line.start
        var leftPt = startPt
        while (true) {
            val segmentEnd = nextTab(text, segmentStart, line.end)
            cells += segmentCells(runs, text, segmentStart, segmentEnd, leftPt, paddingPt, visibleEnd)
            if (segmentEnd >= line.end) break
            val segmentRight = leftPt + advanceWithPadding(
                runs,
                text,
                segmentStart,
                segmentEnd,
                paddingPt,
                visibleEnd,
            )
            val stop = tabs.stopAt(segmentRight)
            val nextStart = segmentEnd + 1
            val nextEnd = nextTab(text, nextStart, line.end)
            val nextWidth = advanceWithPadding(runs, text, nextStart, nextEnd, paddingPt, visibleEnd)
            val nextLeft = when (stop.alignment) {
                TextTabAlignment.LEFT -> stop.positionPt
                TextTabAlignment.CENTER -> stop.positionPt - nextWidth / 2.0
                TextTabAlignment.RIGHT -> stop.positionPt - nextWidth
                TextTabAlignment.DECIMAL -> stop.positionPt - advanceWithPadding(
                    runs,
                    text,
                    nextStart,
                    decimalSeparator(text, nextStart, nextEnd),
                    paddingPt,
                    visibleEnd,
                )
            }
            cells += Cell(segmentEnd, segmentEnd + 1, segmentRight, maxOf(segmentRight, nextLeft), tab = true)
            leftPt = nextLeft
            segmentStart = nextStart
        }
        return cells
    }

    private fun segmentCells(
        runs: List<ShapedRun>,
        text: CharSequence,
        start: Int,
        end: Int,
        leftPt: Double,
        paddingPt: Double,
        visibleEnd: Int,
    ): List<Cell> {
        if (start >= end) return emptyList()
        val rtl = runs.firstOrNull { start >= it.start && start < it.end }?.rtl ?: false
        val total = advanceWithPadding(runs, text, start, end, paddingPt, visibleEnd)
        val cells = ArrayList<Cell>()
        var offset = start
        while (offset < end) {
            val next = offset + Character.charCount(Character.codePointAt(text, offset))
            val from = advanceWithPadding(runs, text, start, offset, paddingPt, visibleEnd)
            val to = advanceWithPadding(runs, text, start, next, paddingPt, visibleEnd)
            cells += if (rtl) {
                Cell(offset, next, leftPt + total - to, leftPt + total - from, tab = false)
            } else {
                Cell(offset, next, leftPt + from, leftPt + to, tab = false)
            }
            offset = next
        }
        return cells
    }

    private fun advanceWithPadding(
        runs: List<ShapedRun>,
        text: CharSequence,
        start: Int,
        end: Int,
        paddingPt: Double,
        visibleEnd: Int,
    ): Double {
        var total = 0.0
        for (run in runs) {
            val from = maxOf(run.start, start)
            val to = minOf(run.end, end)
            if (to <= from) continue
            total += measurer.advance(run.font, run.sizePt, text, from, to, run.rtl)
        }
        if (paddingPt != 0.0) {
            for (offset in start until minOf(end, visibleEnd)) {
                if (text[offset] == ' ') total += paddingPt
            }
        }
        return total
    }

    private fun piecesOf(text: CharSequence, runs: List<ShapedRun>, line: FilledLine): List<Piece> {
        val pieces = ArrayList<Piece>()
        for (run in runs) {
            val from = maxOf(run.start, line.start)
            val to = minOf(run.end, line.end)
            var offset = from
            while (offset < to) {
                val tab = nextTab(text, offset, to)
                if (tab > offset) pieces += Piece(run, offset, tab)
                offset = if (tab < to) tab + 1 else to
            }
        }
        return pieces
    }

    private fun largestSizePt(
        runs: List<ShapedRun>,
        start: Int,
        end: Int,
        fallbackPt: Double,
    ): Double = runs
        .filter { maxOf(it.start, start) < minOf(it.end, end) }
        .maxOfOrNull { it.sizePt.toDouble() }
        ?: fallbackPt

    private fun fontOnLine(runs: List<ShapedRun>, start: Int, end: Int): ResolvedFont? = runs
        .firstOrNull { maxOf(it.start, start) < minOf(it.end, end) }
        ?.font

    private fun alignmentOf(properties: TextParagraphProperties): TextAlignment =
        properties.alignment ?: if (properties.rightToLeft) TextAlignment.RIGHT else TextAlignment.LEFT

    private fun stretches(alignment: TextAlignment, last: Boolean, endsWithBreak: Boolean): Boolean =
        when (alignment) {
            TextAlignment.JUSTIFY, TextAlignment.JUSTIFY_LOW -> !last && !endsWithBreak
            TextAlignment.DISTRIBUTED, TextAlignment.THAI_DISTRIBUTED -> true
            else -> false
        }

    private fun spacesOn(text: CharSequence, start: Int, end: Int): Int {
        var spaces = 0
        for (offset in start until trimmedEnd(text, start, end)) {
            if (text[offset] == ' ') spaces++
        }
        return spaces
    }

    private fun tabStopsOf(properties: TextParagraphProperties): TabStops = TabStops(
        defaultSizePt = (properties.defaultTabSizeEmu ?: TabStops.DEFAULT_TAB_SIZE_EMU) / EMU_PER_POINT,
        stops = properties.tabStops,
    )

    private fun nextTab(text: CharSequence, start: Int, end: Int): Int {
        for (offset in start until end) if (text[offset] == '\t') return offset
        return end
    }

    private fun decimalSeparator(text: CharSequence, start: Int, end: Int): Int {
        for (offset in start until end) if (text[offset] == '.' || text[offset] == ',') return offset
        return end
    }

    private fun trimmedEnd(text: CharSequence, start: Int, end: Int): Int {
        var trimmed = end
        while (trimmed > start && (text[trimmed - 1] == ' ' || text[trimmed - 1] == '\t')) trimmed--
        return trimmed
    }

    private fun localeOf(paragraph: TextParagraph): Locale = paragraph.runs
        .firstNotNullOfOrNull { it.properties.language?.takeIf(String::isNotBlank) }
        ?.let(Locale::forLanguageTag)
        ?: Locale.ROOT

    private class Piece(val run: ShapedRun, val start: Int, val end: Int)

    private class Cell(
        val start: Int,
        val end: Int,
        val leftPt: Double,
        val rightPt: Double,
        val tab: Boolean,
    )

    companion object {

        const val EMU_PER_POINT = 12_700.0
    }
}
