package io.github.pixelrick420.papyrus.viewer.pptx.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TextVerticalLayoutTest {

    private val engine = TextLayoutEngine(
        measurer = BlockMeasurer(),
        breaker = LineBreaker { text, _ -> wordBreaks(text) },
    )

    @Test
    fun `vert turns runs clockwise and lays columns right to left`() {
        val layout = layout(TextVertical.VERT)

        val run = layout.lines.single().runs.single()
        assertEquals(90.0, run.rotationDeg, EPSILON)
        assertEquals(0.0, run.baselinePt, EPSILON)
        assertEquals(200.0 - baselinePt, run.leftPt, EPSILON)

        val character = layout.lines.single().characters.first()
        assertEquals(200.0 - lineHeight, character.leftPt, EPSILON)
        assertEquals(200.0, character.rightPt, EPSILON)
        assertEquals(0.0, character.topPt, EPSILON)
        assertEquals(10.0, character.bottomPt, EPSILON)
    }

    @Test
    fun `vert270 turns runs counter clockwise and lays columns left to right`() {
        val layout = layout(TextVertical.VERT_270)

        val run = layout.lines.single().runs.single()
        assertEquals(270.0, run.rotationDeg, EPSILON)
        assertEquals(100.0, run.baselinePt, EPSILON)

        val character = layout.lines.single().characters.first()
        assertEquals(0.0, character.leftPt, EPSILON)
        assertEquals(lineHeight, character.rightPt, EPSILON)
        assertEquals(90.0, character.topPt, EPSILON)
        assertEquals(100.0, character.bottomPt, EPSILON)
    }

    @Test
    fun `wordArt vertical uses the clockwise turn`() {
        val layout = layout(TextVertical.WORD_ART_VERTICAL)

        assertEquals(90.0, layout.lines.single().runs.single().rotationDeg, EPSILON)
    }

    @Test
    fun `east asian vertical keeps each glyph upright in right to left columns`() {
        val layout = layout(TextVertical.EAST_ASIAN_VERTICAL)

        val runs = layout.lines.single().runs
        assertEquals(listOf("a", "a"), runs.map { it.text })
        assertEquals(listOf(0.0, 0.0), runs.map { it.rotationDeg })
        assertEquals(listOf(0.0, 10.0), runs.map { it.baselinePt })
        assertEquals(200.0 - baselinePt, runs.first().leftPt, EPSILON)
    }

    @Test
    fun `mongolian vertical keeps each glyph upright in left to right columns`() {
        val layout = layout(TextVertical.MONGOLIAN_VERTICAL)

        val runs = layout.lines.single().runs
        assertEquals(listOf("a", "a"), runs.map { it.text })
        assertEquals(listOf(0.0, 10.0), runs.map { it.baselinePt })
        assertEquals(baselinePt, runs.first().leftPt, EPSILON)
    }

    @Test
    fun `vertical rotations are reported separately from the shape rotation`() {
        val orientation = TextOrientation.of(
            TextShapeTransform(rotationDeg = 30.0),
            TextBodyProperties(vertical = TextVertical.VERT_270),
        )

        assertEquals(30.0, orientation.rotationDeg, EPSILON)
        assertEquals(270.0, orientation.verticalRotationDeg, EPSILON)
    }

    private fun layout(vertical: TextVertical): TextFrameLayout = engine.layout(
        TextBody(
            properties = TextBodyProperties(insets = NO_INSETS, vertical = vertical),
            paragraphs = listOf(
                TextParagraph(
                    runs = listOf(TextRun.Text("aa", STYLE)),
                    endProperties = STYLE,
                ),
            ),
        ),
        FRAME_WIDTH_PT * TextLayoutEngine.EMU_PER_POINT,
        FRAME_HEIGHT_PT * TextLayoutEngine.EMU_PER_POINT,
    )

    private fun wordBreaks(text: CharSequence): List<Int> =
        (1 until text.length).filter { text[it - 1] == ' ' }

    private companion object {

        const val EPSILON = 1e-6

        const val FRAME_WIDTH_PT = 200.0

        const val FRAME_HEIGHT_PT = 100.0

        const val lineHeight = 21.6

        const val baselinePt = 21.6

        val NO_INSETS = TextInsets(0.0, 0.0, 0.0, 0.0)

        val STYLE = TextRunProperties(sizePt = 18.0, latinTypeface = "Calibri")
    }
}
