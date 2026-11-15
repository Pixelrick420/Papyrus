package io.github.pixelrick420.papyrus.viewer.pptx.text

import java.text.Bidi as JavaBidi
import java.util.Locale

/**
 * The layout pass (plan §4.7 step 10): each paragraph is flattened, shaped, broken and filled, then
 * turned into positioned runs and per-character boxes. Every length here is in points.
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
        val layouts = bullets.layouts(body)
        val lines = ArrayList<TextLine>()
        var heightPt = 0.0
        body.paragraphs.forEachIndexed { index, paragraph ->
            heightPt = layoutParagraph(
                paragraph = paragraph,
                index = index,
                bullet = layouts[index],
                widthPt = widthPt,
                body = body.properties,
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
        topPt: Double,
        lines: MutableList<TextLine>,
    ): Double {
        val text = shaper.flatten(paragraph)
        val runs = shaper.shape(text, paragraph.properties.rightToLeft, measurer::hasGlyph)
        val metrics = bullet.metrics
        val filled = filler.fill(
            text.text,
            runs,
            text.breaks,
            breaker.breakBefore(text.text, paragraph.properties, localeOf(paragraph)),
            FillMetrics(
                widthPt = widthPt - metrics.textLeftEmu / EMU_PER_POINT -
                    (paragraph.properties.marginRightEmu ?: 0.0) / EMU_PER_POINT,
                firstLineIndentPt = (paragraph.properties.indentEmu ?: 0.0) / EMU_PER_POINT,
                rtl = paragraph.properties.rightToLeft,
                wrap = body.wrap,
            ),
        )
        var top = topPt
        filled.forEachIndexed { lineIndex, line ->
            val sizePt = largestSizePt(runs, line.start, line.end, paragraph.endProperties.sizePt)
            val font = fontOnLine(runs, line.start, line.end)
            val box = lineMetrics.line(
                largestSizePt = sizePt,
                spacing = paragraph.properties.lineSpacing,
                fontAlignment = paragraph.properties.fontAlignment,
                font = font?.let { measurer.metrics(it, sizePt.toFloat()).emBox(sizePt) }
                    ?: FontEmBox.FALLBACK,
            )
            if (lineIndex == 0) {
                top += lineMetrics.spaceBefore(sizePt, paragraph.properties.spaceBefore)
            }
            val leftPt = metrics.textLeftEmu / EMU_PER_POINT + line.startPt
            lines += positioned(
                text = text,
                runs = runs,
                line = line,
                index = lines.size,
                paragraph = index,
                leftPt = leftPt,
                topPt = top,
                heightPt = box.heightPt,
                baselinePt = top + box.baselinePt,
                bullet = if (lineIndex == 0) bullet.marker else BulletMarker.None,
                markerLeftPt = metrics.markerLeftEmu / EMU_PER_POINT,
                markerRightPt = metrics.markerRightEmu / EMU_PER_POINT,
            )
            top += box.heightPt
        }
        return top + lineMetrics.spaceAfter(
            filled.lastOrNull()?.let { largestSizePt(runs, it.start, it.end, paragraph.endProperties.sizePt) }
                ?: paragraph.endProperties.sizePt,
            paragraph.properties.spaceAfter,
        )
    }

    private fun positioned(
        text: ParagraphText,
        runs: List<ShapedRun>,
        line: FilledLine,
        index: Int,
        paragraph: Int,
        leftPt: Double,
        topPt: Double,
        heightPt: Double,
        baselinePt: Double,
        bullet: BulletMarker,
        markerLeftPt: Double,
        markerRightPt: Double,
    ): TextLine {
        val pieces = runs.mapNotNull { run ->
            val start = maxOf(run.start, line.start)
            val end = minOf(run.end, line.end)
            if (end <= start) null else Piece(run, start, end)
        }
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
        val characters = ArrayList<CharacterBox>()
        var left = leftPt
        for (position in order) {
            val piece = pieces[position]
            val width = advance(piece, text.text, piece.start, piece.end)
            glyphRuns += GlyphRun(
                start = piece.start,
                end = piece.end,
                leftPt = left,
                rightPt = left + width,
                baselinePt = baselinePt,
                font = piece.run.font,
                sizePt = piece.run.sizePt.toDouble(),
                properties = piece.run.properties,
                rtl = piece.run.rtl,
            )
            characters += boxes(text.text, piece, left, width, topPt, heightPt)
            left += width
        }
        return TextLine(
            index = index,
            paragraph = paragraph,
            start = line.start,
            end = line.end,
            leftPt = leftPt,
            topPt = topPt,
            widthPt = line.widthPt,
            heightPt = heightPt,
            baselinePt = baselinePt,
            runs = glyphRuns,
            characters = characters,
            bullet = bullet,
            markerLeftPt = markerLeftPt,
            markerRightPt = markerRightPt,
        )
    }

    private fun boxes(
        text: CharSequence,
        piece: Piece,
        leftPt: Double,
        widthPt: Double,
        topPt: Double,
        heightPt: Double,
    ): List<CharacterBox> {
        val boxes = ArrayList<CharacterBox>()
        var offset = piece.start
        while (offset < piece.end) {
            val end = offset + Character.charCount(Character.codePointAt(text, offset))
            val from = advance(piece, text, piece.start, offset)
            val to = advance(piece, text, piece.start, end)
            boxes += if (piece.run.rtl) {
                CharacterBox(offset, end, leftPt + widthPt - to, leftPt + widthPt - from, topPt, topPt + heightPt)
            } else {
                CharacterBox(offset, end, leftPt + from, leftPt + to, topPt, topPt + heightPt)
            }
            offset = end
        }
        return boxes
    }

    private fun advance(piece: Piece, text: CharSequence, start: Int, end: Int): Double = measurer.advance(
        piece.run.font,
        piece.run.sizePt,
        text,
        start,
        end,
        piece.run.rtl,
    ).toDouble()

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

    private fun localeOf(paragraph: TextParagraph): Locale = paragraph.runs
        .firstNotNullOfOrNull { it.properties.language?.takeIf(String::isNotBlank) }
        ?.let(Locale::forLanguageTag)
        ?: Locale.ROOT

    private class Piece(val run: ShapedRun, val start: Int, val end: Int)

    companion object {

        const val EMU_PER_POINT = 12_700.0
    }
}
