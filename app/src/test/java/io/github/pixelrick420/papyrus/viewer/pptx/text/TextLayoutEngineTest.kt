package io.github.pixelrick420.papyrus.viewer.pptx.text

import java.text.BreakIterator
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLayoutEngineTest {

    @Test
    fun `breaks are identical at three widths and two densities`() {
        for (width in WIDTHS_PT) {
            val single = laidOut(engine(1.0), body(paragraph(TEXT.repeat(3))), width).breaks()
            assertTrue("width $width pt did not wrap", single.size > 1)
            assertEquals(single, laidOut(engine(2.0), body(paragraph(TEXT.repeat(3))), width).breaks())
        }
    }

    @Test
    fun `a wider frame takes fewer lines`() {
        val counts = WIDTHS_PT.map { laidOut(engine(), body(paragraph(TEXT.repeat(3))), it).lines.size }

        assertEquals(listOf(11, 7, 5), counts)
    }

    @Test
    fun `baselines step by the 1_2 em box from the frame top`() {
        val lines = laidOut(engine(), body(paragraph(TEXT.repeat(3))), WIDTHS_PT.first()).lines

        val baselines = lines.take(3).map { it.baselinePt }
        assertEquals(21.6, baselines[0], EPSILON)
        assertEquals(43.2, baselines[1], EPSILON)
        assertEquals(64.8, baselines[2], EPSILON)
    }

    @Test
    fun `character boxes are contiguous across the line`() {
        val line = laidOut(engine(), body(paragraph(TEXT.repeat(3))), WIDTHS_PT.first()).lines.first()
        val boxes = line.characters

        assertEquals(line.leftPt, boxes.first().leftPt, EPSILON)
        assertEquals(line.runs.last().rightPt, boxes.last().rightPt, EPSILON)
        assertEquals(0.0, boxes.first().topPt, EPSILON)
        assertEquals(line.heightPt, boxes.first().bottomPt, EPSILON)
        boxes.zipWithNext { left, right ->
            assertEquals(left.rightPt, right.leftPt, EPSILON)
        }
    }

    @Test
    fun `an empty paragraph is one line at the paragraph mark's size`() {
        val layout = laidOut(
            engine(),
            body(TextParagraph(endProperties = TextRunProperties(sizePt = 18.0))),
            WIDTHS_PT.first(),
        )

        assertEquals(1, layout.lines.size)
        assertEquals(21.6, layout.heightPt, EPSILON)
        assertEquals(0, layout.lines.single().characters.size)
    }

    @Test
    fun `wrap none keeps the paragraph on one line`() {
        val layout = laidOut(
            engine(),
            body(
                paragraph(TEXT.repeat(3)),
                TextBodyProperties(insets = NO_INSETS, wrap = TextWrap.NONE),
            ),
            WIDTHS_PT.first(),
        )

        assertEquals(1, layout.lines.size)
        assertTrue(layout.lines.single().widthPt > WIDTHS_PT.first())
    }

    @Test
    fun `a hanging indent keeps bullet text beside the marker`() {
        val bullet = TextParagraph(
            properties = TextParagraphProperties(
                marginLeftEmu = 457_200.0,
                indentEmu = -228_600.0,
                bullet = TextBullet.Character("•", null, null, TextBulletSize.Inherit),
            ),
            runs = listOf(TextRun.Text(TEXT, STYLE)),
            endProperties = STYLE,
        )

        val lines = laidOut(engine(), body(bullet), WIDTHS_PT.first()).lines

        assertEquals(36.0, lines.first().leftPt, EPSILON)
        assertEquals(36.0, lines[1].leftPt, EPSILON)
        assertEquals(18.0, lines.first().markerLeftPt, EPSILON)
        assertEquals(36.0, lines.first().markerRightPt, EPSILON)
        assertTrue(lines.first().bullet != BulletMarker.None)
        assertEquals(BulletMarker.None, lines[1].bullet)
    }

    @Test
    fun `a bullet without inherited indents still leaves room for the marker`() {
        val bullet = TextParagraph(
            properties = TextParagraphProperties(
                bullet = TextBullet.Character("q", "Wingdings", null, TextBulletSize.Inherit),
            ),
            runs = listOf(TextRun.Text(TEXT, STYLE)),
            endProperties = STYLE,
        )

        val lines = laidOut(engine(), body(bullet), WIDTHS_PT.first()).lines

        assertEquals(0.0, lines.first().markerLeftPt, EPSILON)
        assertEquals(18.0, lines.first().markerRightPt, EPSILON)
        assertEquals(18.0, lines.first().leftPt, EPSILON)
        assertTrue(lines.first().leftPt >= lines.first().markerRightPt)
    }

    @Test
    fun `a trailing empty paragraph does not inherit the bullet`() {
        val properties = TextParagraphProperties(bullet = TextBullet.Character("•", null, null, TextBulletSize.Inherit))
        val body = TextBody(
            properties = TextBodyProperties(insets = NO_INSETS),
            paragraphs = listOf(
                TextParagraph(properties = properties, runs = listOf(TextRun.Text(TEXT, STYLE)), endProperties = STYLE),
                TextParagraph(properties = properties, endProperties = STYLE),
            ),
        )

        val lines = laidOut(engine(), body, WIDTHS_PT.first()).lines

        assertTrue(lines.first().bullet != BulletMarker.None)
        assertTrue(lines.drop(1).none { it.bullet != BulletMarker.None })
        assertEquals(lines.first().paragraph, 0)
        assertEquals(lines.last().paragraph, 1)
    }

    private fun laidOut(engine: TextLayoutEngine, body: TextBody, widthPt: Double): TextFrameLayout =
        engine.layout(body, widthPt * TextLayoutEngine.EMU_PER_POINT)

    private fun body(
        paragraph: TextParagraph,
        properties: TextBodyProperties = TextBodyProperties(insets = NO_INSETS),
    ): TextBody = TextBody(properties = properties, paragraphs = listOf(paragraph))

    private fun paragraph(text: String) = TextParagraph(
        runs = listOf(TextRun.Text(text, STYLE)),
        endProperties = STYLE,
    )

    private fun engine(density: Double = 1.0) = TextLayoutEngine(
        measurer = AwtTextMeasurer(density),
        breaker = LineBreaker { text, locale -> boundaries(text, locale) },
    )

    private fun boundaries(text: CharSequence, locale: Locale): List<Int> {
        val iterator = BreakIterator.getLineInstance(locale).apply { setText(text.toString()) }
        val offsets = ArrayList<Int>()
        var index = iterator.next()
        while (index != BreakIterator.DONE && index < text.length) {
            if (index > 0) offsets += index
            index = iterator.next()
        }
        return offsets
    }

    private fun TextFrameLayout.breaks(): List<Int> = lines.flatMap { listOf(it.start, it.end) }

    private companion object {

        const val EPSILON = 1e-6

        const val TEXT_SIZE_PT = 18.0

        const val TEXT =
            "The quick brown fox jumps over the lazy dog while twelve wizards vex a quiet nymph. "

        val WIDTHS_PT = listOf(200.0, 320.0, 480.0)

        val NO_INSETS = TextInsets(0.0, 0.0, 0.0, 0.0)

        val STYLE = TextRunProperties(sizePt = TEXT_SIZE_PT, latinTypeface = "Calibri")
    }
}
