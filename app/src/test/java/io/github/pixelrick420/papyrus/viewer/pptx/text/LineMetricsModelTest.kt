package io.github.pixelrick420.papyrus.viewer.pptx.text

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class LineMetricsModelTest {

    private val model = LineMetricsModel.DEFAULT

    @Test
    fun `a single line is 1_2 times the largest run size`() {
        val box = model.line(18.0)

        assertEquals(21.6, box.heightPt, EPSILON)
    }

    @Test
    fun `spcPct multiplies the 1_2 em box`() {
        val box = model.line(18.0, TextSpacing(percent = 150.0))

        assertEquals(32.4, box.heightPt, EPSILON)
    }

    @Test
    fun `spcPts keeps its pitch when a larger run shares the line`() {
        val box = model.line(36.0, TextSpacing(points = 24.0))

        assertEquals(24.0, box.heightPt, EPSILON)
    }

    @Test
    fun `a percent paragraph space is a share of the 1_2 em box`() {
        assertEquals(10.8, model.spaceBefore(18.0, TextSpacing(percent = 50.0)), EPSILON)
        assertEquals(10.8, model.spaceAfter(18.0, TextSpacing(percent = 50.0)), EPSILON)
    }

    @Test
    fun `a point paragraph space ignores an autofit scale that a percent follows`() {
        assertEquals(6.0, model.spaceBefore(9.0, TextSpacing(points = 6.0)), EPSILON)
        assertEquals(5.4, model.spaceBefore(9.0, TextSpacing(percent = 50.0)), EPSILON)
    }

    @Test
    fun `no spacing means no paragraph space and the natural line`() {
        assertEquals(0.0, model.spaceBefore(18.0, null), EPSILON)
        assertEquals(21.6, model.line(18.0, TextSpacing()).heightPt, EPSILON)
    }

    @Test
    fun `the first baseline rule splits the line box by the font metrics`() {
        val ascent = FONT.ascentEm * 18.0
        val descent = FONT.descentEm * 18.0
        val height = model.line(18.0).heightPt

        val share = model.line(18.0, metrics = FONT)
        val balanced = LineMetricsModel(firstBaselineRule = FirstBaselineRule.BALANCED_LEADING)
            .line(18.0, metrics = FONT)
        val above = LineMetricsModel(firstBaselineRule = FirstBaselineRule.LEADING_ABOVE)
            .line(18.0, metrics = FONT)

        assertEquals(height * ascent / (ascent + descent), share.baselinePt, EPSILON)
        assertEquals((height + ascent - descent) / 2, balanced.baselinePt, EPSILON)
        assertEquals(height, above.baselinePt, EPSILON)
    }

    @Test
    fun `a line with no font metrics falls back to the built-in ascent and descent`() {
        val ascent = LineMetricsModel.FALLBACK_ASCENT_EM * 18.0
        val descent = LineMetricsModel.FALLBACK_DESCENT_EM * 18.0

        val box = model.line(18.0)

        assertEquals(21.6 * ascent / (ascent + descent), box.baselinePt, EPSILON)
    }

    @Test
    fun `fontAlgn moves the baseline inside the line box`() {
        val ascent = (FONT.ascentEm * 18.0)
        val descent = (FONT.descentEm * 18.0)
        val height = model.line(18.0).heightPt

        assertEquals(
            ascent,
            model.line(18.0, fontAlignment = TextFontAlignment.TOP, metrics = FONT).baselinePt,
            EPSILON,
        )
        assertEquals(
            height - descent,
            model.line(18.0, fontAlignment = TextFontAlignment.BOTTOM, metrics = FONT).baselinePt,
            EPSILON,
        )
        assertEquals(
            (height + ascent - descent) / 2,
            model.line(18.0, fontAlignment = TextFontAlignment.CENTER, metrics = FONT).baselinePt,
            EPSILON,
        )
    }

    private companion object {

        const val EPSILON = 1e-9

        val FONT = SfntMetrics.parse(
            File("src/main/assets/ooxml/fonts/metric/PptxSans-Regular.ttf").readBytes(),
        )!!
    }
}
