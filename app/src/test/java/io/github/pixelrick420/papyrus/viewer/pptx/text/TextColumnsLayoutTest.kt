package io.github.pixelrick420.papyrus.viewer.pptx.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TextColumnsLayoutTest {

    private val engine = TextLayoutEngine(
        measurer = BlockMeasurer(),
        breaker = LineBreaker { text, _ -> wordBreaks(text) },
    )

    @Test
    fun `two columns use the space between them`() {
        val layout = layout(columnCount = 2, columnSpacingPt = 10.0, paragraphs = 6)

        assertEquals(listOf(0.0, 0.0, 0.0, 0.0, 105.0, 105.0), layout.lines.map { it.leftPt })
        assertEquals(86.4, layout.heightPt, EPSILON)
    }

    @Test
    fun `rtlCol fills the right column first`() {
        val layout = layout(columnCount = 2, columnSpacingPt = 10.0, paragraphs = 6, rightToLeft = true)

        assertEquals(listOf(105.0, 105.0, 105.0, 105.0, 0.0, 0.0), layout.lines.map { it.leftPt })
    }

    @Test
    fun `column width limits line breaking`() {
        val wide = layout(columnCount = 1, paragraphs = 1, text = TEXT)
        val narrow = layout(columnCount = 2, columnSpacingPt = 10.0, paragraphs = 1, text = TEXT)

        assertEquals(145.0, wide.lines.single().widthPt, EPSILON)
        assertEquals(85.0, narrow.lines.first().widthPt, EPSILON)
    }

    @Test
    fun `vertical columns keep the run rotation`() {
        val layout = layout(
            columnCount = 2,
            columnSpacingPt = 10.0,
            paragraphs = 6,
            vertical = TextVertical.VERT,
        )

        assertEquals(90.0, layout.lines.first().runs.single().rotationDeg, EPSILON)
    }

    private fun layout(
        columnCount: Int,
        columnSpacingPt: Double = 0.0,
        paragraphs: Int = 1,
        text: String = "a",
        rightToLeft: Boolean = false,
        vertical: TextVertical = TextVertical.HORZ,
    ): TextFrameLayout = engine.layout(
        TextBody(
            properties = TextBodyProperties(
                insets = NO_INSETS,
                vertical = vertical,
                columnCount = columnCount,
                columnSpacingEmu = columnSpacingPt * TextLayoutEngine.EMU_PER_POINT,
                rightToLeftColumns = rightToLeft,
            ),
            paragraphs = (0 until paragraphs).map {
                TextParagraph(
                    runs = listOf(TextRun.Text(text, STYLE)),
                    endProperties = STYLE,
                )
            },
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

        const val TEXT = "a a a a a a a a a a"

        val NO_INSETS = TextInsets(0.0, 0.0, 0.0, 0.0)

        val STYLE = TextRunProperties(sizePt = 18.0, latinTypeface = "Calibri")
    }
}
