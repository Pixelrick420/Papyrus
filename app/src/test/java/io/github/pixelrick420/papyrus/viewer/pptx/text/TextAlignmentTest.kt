package io.github.pixelrick420.papyrus.viewer.pptx.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TextAlignmentTest {

    private val engine = TextLayoutEngine(
        measurer = BlockMeasurer(),
        breaker = LineBreaker { text, _ -> wordBreaks(text) },
    )

    @Test
    fun `a centred line sits in the middle of the text rectangle`() {
        val line = layout(TextParagraphProperties(alignment = TextAlignment.CENTER)).lines.single()

        assertEquals(77.5, line.leftPt, EPSILON)
        assertEquals(45.0, line.widthPt, EPSILON)
    }

    @Test
    fun `a right aligned line ends at the right inset`() {
        val line = layout(TextParagraphProperties(alignment = TextAlignment.RIGHT)).lines.single()

        assertEquals(155.0, line.leftPt, EPSILON)
    }

    @Test
    fun `right to left mirrors the default alignment`() {
        val line = layout(TextParagraphProperties(rightToLeft = true)).lines.single()

        assertEquals(155.0, line.leftPt, EPSILON)
    }

    @Test
    fun `justify stretches the spaces on every line but the last`() {
        val lines = layout(
            TextParagraphProperties(alignment = TextAlignment.JUSTIFY),
            text = "aaaa bbbb cccc",
            widthPt = 100.0,
        ).lines

        assertEquals(100.0, lines[0].widthPt, EPSILON)
        assertEquals(20.0, lines[0].characters[4].rightPt - lines[0].characters[4].leftPt, EPSILON)
        assertEquals(40.0, lines[1].widthPt, EPSILON)
    }

    @Test
    fun `distributed stretches the last line too`() {
        val body = "aaaa bbbb cccc dddd"
        val justified = layout(
            TextParagraphProperties(alignment = TextAlignment.JUSTIFY),
            text = body,
            widthPt = 100.0,
        ).lines
        val distributed = layout(
            TextParagraphProperties(alignment = TextAlignment.DISTRIBUTED),
            text = body,
            widthPt = 100.0,
        ).lines

        assertEquals(85.0, justified.last().widthPt, EPSILON)
        assertEquals(100.0, distributed.last().widthPt, EPSILON)
    }

    @Test
    fun `a line ended by a stored break is not stretched`() {
        val runs = listOf(
            TextRun.Text("aa bb", STYLE),
            TextRun.Break(STYLE),
            TextRun.Text("cc", STYLE),
        )

        val lines = layout(
            TextParagraphProperties(alignment = TextAlignment.JUSTIFY),
            runs = runs,
        ).lines

        assertEquals(45.0, lines.first().widthPt, EPSILON)
    }

    @Test
    fun `a left tab jumps to the default pitch`() {
        val lines = layout(text = "a\tb").lines

        assertEquals(72.0, lines.single().runs[1].leftPt, EPSILON)
    }

    @Test
    fun `a centre tab centres the next text on the stop`() {
        val lines = layout(
            TextParagraphProperties(
                tabStops = listOf(TextTabStop(tab(50.0), TextTabAlignment.CENTER)),
            ),
            text = "a\tb",
        ).lines

        assertEquals(45.0, lines.single().runs[1].leftPt, EPSILON)
    }

    @Test
    fun `a right tab ends the next text at the stop`() {
        val lines = layout(
            TextParagraphProperties(
                tabStops = listOf(TextTabStop(tab(50.0), TextTabAlignment.RIGHT)),
            ),
            text = "a\tb",
        ).lines

        assertEquals(40.0, lines.single().runs[1].leftPt, EPSILON)
    }

    @Test
    fun `a decimal tab lines the separator up with the stop`() {
        val lines = layout(
            TextParagraphProperties(
                tabStops = listOf(TextTabStop(tab(50.0), TextTabAlignment.DECIMAL)),
            ),
            text = "1\t1.5",
        ).lines

        val line = lines.single()
        assertEquals(40.0, line.runs[1].leftPt, EPSILON)
        assertEquals(50.0, line.characters.first { it.start == 3 }.leftPt, EPSILON)
    }

    private fun tab(positionPt: Double): Double = positionPt * TextLayoutEngine.EMU_PER_POINT

    private fun layout(
        properties: TextParagraphProperties = TextParagraphProperties(),
        text: String = "aa bb",
        widthPt: Double = 200.0,
        runs: List<TextRun> = listOf(TextRun.Text(text, STYLE)),
    ): TextFrameLayout = engine.layout(
        TextBody(
            properties = TextBodyProperties(insets = NO_INSETS),
            paragraphs = listOf(
                TextParagraph(properties = properties, runs = runs, endProperties = STYLE),
            ),
        ),
        widthPt * TextLayoutEngine.EMU_PER_POINT,
    )

    private fun wordBreaks(text: CharSequence): List<Int> =
        (1 until text.length).filter { text[it - 1] == ' ' }

    private companion object {

        const val EPSILON = 1e-6

        val NO_INSETS = TextInsets(0.0, 0.0, 0.0, 0.0)

        val STYLE = TextRunProperties(sizePt = 18.0, latinTypeface = "Calibri")
    }
}
