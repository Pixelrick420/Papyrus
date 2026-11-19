package io.github.pixelrick420.papyrus.viewer.pptx.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TextPlacementTest {

    private val engine = TextLayoutEngine(
        measurer = BlockMeasurer(),
        breaker = LineBreaker { text, _ -> wordBreaks(text) },
    )

    @Test
    fun `the top anchor starts the block at the text rectangle top`() {
        val layout = layout(heightPt = FRAME_HEIGHT_PT)

        assertEquals(0.0, layout.lines.single().topPt, EPSILON)
    }

    @Test
    fun `the centre anchor splits the slack above and below`() {
        val layout = layout(
            properties = TextBodyProperties(insets = NO_INSETS, anchor = TextAnchor.CENTER),
            heightPt = FRAME_HEIGHT_PT,
        )

        assertEquals((FRAME_HEIGHT_PT - LINE_HEIGHT_PT) / 2.0, layout.lines.single().topPt, EPSILON)
    }

    @Test
    fun `the bottom anchor ends the block at the text rectangle bottom`() {
        val layout = layout(
            properties = TextBodyProperties(insets = NO_INSETS, anchor = TextAnchor.BOTTOM),
            heightPt = FRAME_HEIGHT_PT,
        )

        val line = layout.lines.single()
        assertEquals(FRAME_HEIGHT_PT, line.topPt + line.heightPt, EPSILON)
    }

    @Test
    fun `a justified anchor spreads the slack between lines`() {
        val layout = layout(
            texts = listOf("aa", "aa"),
            properties = TextBodyProperties(insets = NO_INSETS, anchor = TextAnchor.JUSTIFIED),
            heightPt = FRAME_HEIGHT_PT,
        )

        val slack = FRAME_HEIGHT_PT - 2 * LINE_HEIGHT_PT
        assertEquals(0.0, layout.lines[0].topPt, EPSILON)
        assertEquals(LINE_HEIGHT_PT + slack, layout.lines[1].topPt, EPSILON)
    }

    @Test
    fun `overflow at the bottom anchor grows past the text rectangle`() {
        val layout = layout(
            properties = TextBodyProperties(insets = NO_INSETS, anchor = TextAnchor.BOTTOM),
            heightPt = 10.0,
        )

        val line = layout.lines.single()
        assertEquals(-(LINE_HEIGHT_PT - 10.0), line.topPt, EPSILON)
        assertEquals(10.0, line.topPt + line.heightPt, EPSILON)
    }

    @Test
    fun `a centred block still centres when it overflows`() {
        val layout = layout(
            properties = TextBodyProperties(insets = NO_INSETS, anchor = TextAnchor.CENTER),
            heightPt = 10.0,
        )

        assertEquals(-(LINE_HEIGHT_PT - 10.0) / 2.0, layout.lines.single().topPt, EPSILON)
    }

    @Test
    fun `anchor centre centres the block horizontally`() {
        val layout = layout(
            properties = TextBodyProperties(insets = NO_INSETS, anchorCenter = true),
            heightPt = FRAME_HEIGHT_PT,
        )

        assertEquals((WIDTH_PT - LINE_WIDTH_PT) / 2.0, layout.lines.single().leftPt, EPSILON)
        assertEquals((WIDTH_PT - LINE_WIDTH_PT) / 2.0, layout.lines.single().characters.first().leftPt, EPSILON)
    }

    @Test
    fun `anchor centre uses the widest line for the block`() {
        val layout = layout(
            texts = listOf("aa", "aaaa"),
            properties = TextBodyProperties(insets = NO_INSETS, anchorCenter = true),
            heightPt = FRAME_HEIGHT_PT,
        )

        assertEquals((WIDTH_PT - 2 * LINE_WIDTH_PT) / 2.0, layout.lines[0].leftPt, EPSILON)
        assertEquals((WIDTH_PT - 2 * LINE_WIDTH_PT) / 2.0, layout.lines[1].leftPt, EPSILON)
    }

    @Test
    fun `a shape rotation turns the text`() {
        val orientation = TextOrientation.of(
            TextShapeTransform(rotationDeg = 90.0),
            TextBodyProperties(),
        )

        assertEquals(90.0, orientation.rotationDeg, EPSILON)
    }

    @Test
    fun `flip vertical adds a half turn`() {
        val orientation = TextOrientation.of(TextShapeTransform(flipV = true), TextBodyProperties())

        assertEquals(180.0, orientation.rotationDeg, EPSILON)
    }

    @Test
    fun `flip horizontal never mirrors the text`() {
        val orientation = TextOrientation.of(TextShapeTransform(flipH = true), TextBodyProperties())

        assertEquals(0.0, orientation.rotationDeg, EPSILON)
    }

    @Test
    fun `upright ignores the shape rotation but keeps the body rotation`() {
        val orientation = TextOrientation.of(
            TextShapeTransform(rotationDeg = 90.0),
            TextBodyProperties(rotation = 15.0, upright = true),
        )

        assertEquals(15.0, orientation.rotationDeg, EPSILON)
    }

    @Test
    fun `the body rotation adds to the shape rotation and wraps`() {
        val orientation = TextOrientation.of(
            TextShapeTransform(rotationDeg = 20.0),
            TextBodyProperties(rotation = 350.0),
        )

        assertEquals(10.0, orientation.rotationDeg, EPSILON)
    }

    @Test
    fun `the frame layout reports the orientation`() {
        val layout = layout(shape = TextShapeTransform(flipV = true))

        assertEquals(180.0, layout.orientation.rotationDeg, EPSILON)
    }

    private fun layout(
        texts: List<String> = listOf("aa"),
        properties: TextBodyProperties = TextBodyProperties(insets = NO_INSETS),
        heightPt: Double? = null,
        shape: TextShapeTransform = TextShapeTransform.NONE,
    ): TextFrameLayout = engine.layout(
        TextBody(
            properties = properties,
            paragraphs = texts.map { text ->
                TextParagraph(
                    runs = listOf(TextRun.Text(text, STYLE)),
                    endProperties = STYLE,
                )
            },
        ),
        WIDTH_PT * TextLayoutEngine.EMU_PER_POINT,
        heightPt?.times(TextLayoutEngine.EMU_PER_POINT),
        shape,
    )

    private fun wordBreaks(text: CharSequence): List<Int> =
        (1 until text.length).filter { text[it - 1] == ' ' }

    private companion object {

        const val EPSILON = 1e-6

        const val WIDTH_PT = 200.0

        const val FRAME_HEIGHT_PT = 200.0

        const val LINE_HEIGHT_PT = 21.6

        const val LINE_WIDTH_PT = 20.0

        val NO_INSETS = TextInsets(0.0, 0.0, 0.0, 0.0)

        val STYLE = TextRunProperties(sizePt = 18.0, latinTypeface = "Calibri")
    }
}
