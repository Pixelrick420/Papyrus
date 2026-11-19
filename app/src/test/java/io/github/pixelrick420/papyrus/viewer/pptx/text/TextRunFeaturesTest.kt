package io.github.pixelrick420.papyrus.viewer.pptx.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The C-RUN probes for `kern`, ligatures and `spc`: widths must agree between filling and placing. */
class TextRunFeaturesTest {

    private val measurer = ShapingRecorder()

    private val engine = TextLayoutEngine(
        measurer = measurer,
        breaker = LineBreaker { text, _ -> (1 until text.length).toList() },
    )

    @Test
    fun `kern at or above the run size kerns the run`() {
        val layout = layout("AV", TextRunProperties(sizePt = 14.0, kerningPt = 12.0))

        assertEquals(KERNED_PT + KERNED_PT, layout.lines.single().widthPt, EPSILON)
        assertTrue(measurer.calls.all { it.features.kerning })
    }

    @Test
    fun `kern below the run size leaves the run unkerned`() {
        val layout = layout("AV", TextRunProperties(sizePt = 10.0, kerningPt = 12.0))

        assertEquals(PLAIN_PT + PLAIN_PT, layout.lines.single().widthPt, EPSILON)
        assertTrue(measurer.calls.none { it.features.kerning })
    }

    @Test
    fun `kern zero kerns at every size`() {
        val layout = layout("AV", TextRunProperties(sizePt = 6.0, kerningPt = 0.0))

        assertEquals(KERNED_PT + KERNED_PT, layout.lines.single().widthPt, EPSILON)
        assertTrue(measurer.calls.all { it.features.kerning })
    }

    @Test
    fun `an absent kern kerns at every size`() {
        val layout = layout("AV", TextRunProperties(sizePt = 6.0))

        assertEquals(KERNED_PT + KERNED_PT, layout.lines.single().widthPt, EPSILON)
        assertTrue(measurer.calls.all { it.features.kerning })
    }

    @Test
    fun `ligature probes stay unligated`() {
        // A threshold above the run size keeps kerning out of the probe.
        val layout = layout("ffi", TextRunProperties(sizePt = 10.0, kerningPt = 100.0))

        assertEquals(PLAIN_PT * 3, layout.lines.single().widthPt, EPSILON)
        assertTrue(measurer.calls.none { it.features.ligatures })
    }

    @Test
    fun `spc widens every character by its stored points`() {
        val layout = layout("AV", TextRunProperties(sizePt = 10.0, kerningPt = 100.0, spacingPt = 1.5))

        assertEquals((PLAIN_PT + 1.5) * 2, layout.lines.single().widthPt, EPSILON)
        assertTrue(measurer.calls.all { it.letterSpacingEm == 0.15f })
    }

    @Test
    fun `negative spc tightens the run by the same measure`() {
        val layout = layout("AV", TextRunProperties(sizePt = 10.0, kerningPt = 100.0, spacingPt = -2.0))

        assertEquals((PLAIN_PT - 2.0) * 2, layout.lines.single().widthPt, EPSILON)
        assertTrue(measurer.calls.all { it.letterSpacingEm == -0.2f })
    }

    @Test
    fun `filling and placing measure with one set of features`() {
        val layout = layout(
            "aaaa bbbb cccc",
            TextRunProperties(sizePt = 10.0, kerningPt = 8.0, spacingPt = 0.5),
            widthPt = 40.0,
        )

        val widths = layout.lines.map { it.widthPt }
        assertEquals(listOf((KERNED_PT + 0.5) * 4, (KERNED_PT + 0.5) * 4, (KERNED_PT + 0.5) * 4), widths)
        assertTrue(measurer.calls.all { it.features.kerning && it.letterSpacingEm == 0.05f })
    }

    private fun layout(
        text: String,
        properties: TextRunProperties,
        widthPt: Double = 1_000.0,
    ): TextFrameLayout = engine.layout(
        TextBody(
            properties = TextBodyProperties(insets = TextInsets(0.0, 0.0, 0.0, 0.0)),
            paragraphs = listOf(
                TextParagraph(runs = listOf(TextRun.Text(text, properties))),
            ),
        ),
        frameWidthEmu = widthPt * TextLayoutEngine.EMU_PER_POINT,
    )

    private class ShapingRecorder : TextMeasurer {

        class Call(val features: FontFeatures, val letterSpacingEm: Float)

        val calls = ArrayList<Call>()

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
            letterSpacingEm: Float,
        ): Float {
            calls += Call(features, letterSpacingEm)
            val glyphs = end - start
            val plain = if (features.kerning) KERNED_PT else PLAIN_PT
            return (glyphs * (plain + letterSpacingEm * sizePt)).toFloat()
        }

        override fun offsetForAdvance(
            font: ResolvedFont,
            sizePt: Float,
            text: CharSequence,
            start: Int,
            end: Int,
            rtl: Boolean,
            features: FontFeatures,
            letterSpacingEm: Float,
            maxAdvance: Float,
        ): Int = end
    }

    private companion object {

        const val EPSILON = 1e-6

        const val PLAIN_PT = 10.0

        const val KERNED_PT = 9.0
    }
}
