package io.github.pixelrick420.papyrus.viewer.pptx.text

import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The C-RUN probes for caps, `baseline`, `u` and `strike`: shape, then place, then draw. */
class TextRunEffectsTest {

    private val shaper = TextShaper()

    private val engine = TextLayoutEngine(
        measurer = BlockMeasurer(charPt = 10.0),
        breaker = LineBreaker { text, _ -> (1 until text.length).toList() },
    )

    @Test
    fun `caps all uppercases the flattened text`() {
        val text = shaper.flatten(paragraph("ab", TextRunProperties(caps = TextCaps.ALL)))

        assertEquals("AB", text.text)
        assertEquals(2, text.styles.size)
    }

    @Test
    fun `caps small uppercases and shrinks the shaped run`() {
        val properties = TextRunProperties(sizePt = 10.0, caps = TextCaps.SMALL)

        val runs = shaper.shape(shaper.flatten(paragraph("ab", properties)), baseRtl = false)

        assertEquals("AB", shaper.flatten(paragraph("ab", properties)).text)
        assertEquals(8.0f, runs.single().sizePt, EPSILON_F)
    }

    @Test
    fun `caps none keeps the text and size`() {
        val properties = TextRunProperties(sizePt = 10.0)

        val runs = shaper.shape(shaper.flatten(paragraph("ab", properties)), baseRtl = false)

        assertEquals(10.0f, runs.single().sizePt, EPSILON_F)
        assertEquals("ab", shaper.flatten(paragraph("ab", properties)).text)
    }

    @Test
    fun `a positive baseline raises the run`() {
        val line = layout(TextRunProperties(sizePt = 10.0, baseline = 0.3)).lines.single()

        assertEquals(3.0, line.baselinePt - line.runs.single().baselinePt, EPSILON)
    }

    @Test
    fun `a negative baseline lowers the run`() {
        val line = layout(TextRunProperties(sizePt = 10.0, baseline = -0.25)).lines.single()

        assertEquals(-2.5, line.baselinePt - line.runs.single().baselinePt, EPSILON)
    }

    @Test
    fun `no baseline shift keeps the run on the line baseline`() {
        val line = layout(TextRunProperties(sizePt = 10.0)).lines.single()

        assertEquals(line.baselinePt, line.runs.single().baselinePt, EPSILON)
    }

    @Test
    fun `an underline takes the font rule geometry and its own paint`() {
        val fill = ScenePaint.Solid(Rgba(255, 0, 0))
        val properties = TextRunProperties(
            sizePt = 10.0,
            underline = TextUnderline(style = "sng", fill = fill),
        )

        val decoration = layout(properties).lines.single().decorations.single()

        assertEquals(TextDecorationKind.UNDERLINE, decoration.kind)
        assertEquals("sng", decoration.style)
        assertEquals(1.0, decoration.offsetPt, EPSILON)
        assertEquals(0.5, decoration.thicknessPt, EPSILON)
        assertSame(fill, decoration.paint)
    }

    @Test
    fun `a run fill is used when the underline has none`() {
        val properties = TextRunProperties(
            sizePt = 10.0,
            underline = TextUnderline(style = "sng"),
        )

        val decoration = layout(properties).lines.single().decorations.single()

        assertEquals(null, decoration.paint)
    }

    @Test
    fun `a strike sits above the baseline and keeps its variant`() {
        val properties = TextRunProperties(sizePt = 10.0, strike = TextStrike.DOUBLE)

        val decoration = layout(properties).lines.single().decorations.single()

        assertEquals(TextDecorationKind.STRIKE, decoration.kind)
        assertEquals("dblStrike", decoration.style)
        assertEquals(-2.5, decoration.offsetPt, EPSILON)
        assertEquals(0.5, decoration.thicknessPt, EPSILON)
    }

    @Test
    fun `underline and strike can share one run`() {
        val properties = TextRunProperties(
            sizePt = 10.0,
            underline = TextUnderline(style = "dbl"),
            strike = TextStrike.SINGLE,
        )

        val decorations = layout(properties).lines.single().decorations

        assertEquals(
            listOf(TextDecorationKind.UNDERLINE, TextDecorationKind.STRIKE),
            decorations.map { it.kind },
        )
        assertEquals(1.0, decorations[0].offsetPt, EPSILON)
        assertEquals(-2.5, decorations[1].offsetPt, EPSILON)
    }

    @Test
    fun `no underline and no strike draw no rules`() {
        val decorations = layout(TextRunProperties(sizePt = 10.0)).lines.single().decorations

        assertTrue(decorations.isEmpty())
    }

    @Test
    fun `a rule spans the run's own glyphs`() {
        val properties = TextRunProperties(sizePt = 10.0, underline = TextUnderline(style = "sng"))

        val line = layout(properties, text = "abcd").lines.single()
        val decoration = line.decorations.single()

        assertEquals(0.0, decoration.leftPt, EPSILON)
        assertEquals(40.0, decoration.rightPt, EPSILON)
    }

    private fun paragraph(value: String, properties: TextRunProperties): TextParagraph =
        TextParagraph(runs = listOf(TextRun.Text(value, properties)))

    private fun layout(
        properties: TextRunProperties,
        text: String = "ab",
    ): TextFrameLayout = engine.layout(
        TextBody(
            properties = TextBodyProperties(insets = TextInsets(0.0, 0.0, 0.0, 0.0)),
            paragraphs = listOf(paragraph(text, properties)),
        ),
        frameWidthEmu = 1_000.0 * TextLayoutEngine.EMU_PER_POINT,
    )

    private companion object {

        const val EPSILON = 1e-6

        const val EPSILON_F = 1e-4f
    }
}
