package io.github.pixelrick420.papyrus.viewer.pptx.text

import org.junit.Assert.assertEquals
import org.junit.Test

class LineFillerTest {

    private val text = "aaaa bbbb cccc"

    private val segments = listOf(
        ShapedRun(0, text.length, 0, FONT, 18f, TextRunProperties()),
    )

    private val filler = LineFiller(MEASURER)

    @Test
    fun `a greedy line breaks at the last space that fits and drops its width`() {
        val lines = filler.fill(text, segments, emptyList(), anyBreak(text), FillMetrics(widthPt = 50.0))

        assertEquals(listOf(0, 5, 10), lines.map { it.start })
        assertEquals(listOf(5, 10, 14), lines.map { it.end })
        assertEquals(listOf(40.0, 40.0, 40.0), lines.map { it.widthPt })
    }

    @Test
    fun `wrap none keeps the whole paragraph on one line`() {
        val lines = filler.fill(
            text,
            segments,
            emptyList(),
            anyBreak(text),
            FillMetrics(widthPt = 50.0, wrap = TextWrap.NONE),
        )

        assertEquals(1, lines.size)
        assertEquals(14, lines.single().end)
        assertEquals(130.0, lines.single().widthPt, EPSILON)
    }

    @Test
    fun `a word wider than the line is split instead of overflowing it`() {
        val word = "aaaaaaaaaa"
        val runs = listOf(ShapedRun(0, word.length, 0, FONT, 18f, TextRunProperties()))

        val lines = filler.fill(word, runs, emptyList(), BooleanArray(word.length + 1), FillMetrics(30.0))

        assertEquals(listOf(3, 6, 9, 10), lines.map { it.end })
        assertEquals(30.0, lines[0].widthPt, EPSILON)
        assertEquals(10.0, lines.last().widthPt, EPSILON)
    }

    @Test
    fun `the first line takes the indent out of its own width`() {
        val lines = filler.fill(
            "aaaa",
            listOf(ShapedRun(0, 4, 0, FONT, 18f, TextRunProperties())),
            emptyList(),
            anyBreak("aaaa"),
            FillMetrics(widthPt = 50.0, firstLineIndentPt = 25.0),
        )

        assertEquals(25.0, lines.first().startPt, EPSILON)
        assertEquals(2, lines.first().end)
        assertEquals(20.0, lines.first().widthPt, EPSILON)
        assertEquals(0.0, lines.last().startPt, EPSILON)
    }

    @Test
    fun `a stored break starts a line and only the first line is indented`() {
        val lines = filler.fill(
            "aabb",
            listOf(ShapedRun(0, 4, 0, FONT, 18f, TextRunProperties())),
            listOf(2),
            anyBreak("aabb"),
            FillMetrics(widthPt = 100.0, firstLineIndentPt = 25.0),
        )

        assertEquals(listOf(0, 2), lines.map { it.start })
        assertEquals(25.0, lines.first().startPt, EPSILON)
        assertEquals(0.0, lines.last().startPt, EPSILON)
    }

    @Test
    fun `a trailing stored break leaves an empty line`() {
        val lines = filler.fill(
            "ab",
            listOf(ShapedRun(0, 2, 0, FONT, 18f, TextRunProperties())),
            listOf(2),
            anyBreak("ab"),
            FillMetrics(widthPt = 100.0),
        )

        assertEquals(2, lines.size)
        assertEquals(2, lines.last().start)
        assertEquals(0.0, lines.last().widthPt, EPSILON)
    }

    private fun anyBreak(value: CharSequence): BooleanArray =
        BooleanArray(value.length + 1) { it > 0 }

    private class BlockMeasurer : TextMeasurer {

        override fun metrics(font: ResolvedFont, sizePt: Float): FontMetricsPt =
            FontMetricsPt(ascentPt = sizePt * 0.8, descentPt = sizePt * 0.2)

        override fun advance(
            font: ResolvedFont,
            sizePt: Float,
            text: CharSequence,
            start: Int,
            end: Int,
            rtl: Boolean,
            features: FontFeatures,
        ): Float = (start until end).sumOf { widthOf(text[it]) }.toFloat()

        override fun offsetForAdvance(
            font: ResolvedFont,
            sizePt: Float,
            text: CharSequence,
            start: Int,
            end: Int,
            rtl: Boolean,
            features: FontFeatures,
            maxAdvance: Float,
        ): Int {
            var width = 0.0
            var offset = start
            while (offset < end) {
                width += widthOf(text[offset])
                offset++
                if (width >= maxAdvance) return offset
            }
            return end
        }

        private fun widthOf(char: Char): Double = if (char == ' ') SPACE_PT else CHAR_PT

        private companion object {
            const val CHAR_PT = 10.0

            const val SPACE_PT = 5.0
        }
    }

    private companion object {

        const val EPSILON = 1e-9

        val FONT = ResolvedFont(FontChoice.System(null, FontSubstitution.SYSTEM_SANS, compatible = true))

        val MEASURER = BlockMeasurer()
    }
}
