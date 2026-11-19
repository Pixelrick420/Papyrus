package io.github.pixelrick420.papyrus.viewer.pptx.text

import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoNumbersTest {

    private val numbers = AutoNumbers()

    private val red = Rgba(0xFF, 0x00, 0x00)

    @Test
    fun `arabic schemes carry their punctuation`() {
        assertEquals("3.", numbers.format("arabicPeriod", 3))
        assertEquals("3)", numbers.format("arabicParenR", 3))
        assertEquals("(3)", numbers.format("arabicParenBoth", 3))
        assertEquals("3", numbers.format("arabicPlain", 3))
    }

    @Test
    fun `alpha schemes continue in repeated letters`() {
        assertEquals("a.", numbers.format("alphaLcPeriod", 1))
        assertEquals("z.", numbers.format("alphaLcPeriod", 26))
        assertEquals("aa.", numbers.format("alphaLcPeriod", 27))
        assertEquals("bb.", numbers.format("alphaLcPeriod", 28))
        assertEquals("D)", numbers.format("alphaUcParenR", 4))
    }

    @Test
    fun `roman schemes subtract before adding`() {
        assertEquals("iv.", numbers.format("romanLcPeriod", 4))
        assertEquals("IX.", numbers.format("romanUcPeriod", 9))
        assertEquals("XL.", numbers.format("romanUcPeriod", 40))
        assertEquals("MCMXCIV.", numbers.format("romanUcPeriod", 1994))
    }

    @Test
    fun `circled schemes pick their own digit set`() {
        assertEquals("\u2460", numbers.format("circleNumDbPlain", 1))
        assertEquals("\u2469", numbers.format("circleNumDbPlain", 10))
        assertEquals("\u278A", numbers.format("circleNumWdBlackPlain", 1))
        assertEquals("\u2780", numbers.format("circleNumWdWhitePlain", 1))
        assertEquals("21", numbers.format("circleNumDbPlain", 21))
    }

    @Test
    fun `enclosed decimal schemes pick their own enclosure`() {
        assertEquals("\u2463", numbers.format("decimalEnclosedCircle", 4))
        assertEquals("\u2477", numbers.format("decimalEnclosedParen", 4))
        assertEquals("\u248B", numbers.format("decimalEnclosedFullstop", 4))
        assertEquals("(21)", numbers.format("decimalEnclosedParen", 21))
        assertEquals("21.", numbers.format("decimalEnclosedFullstop", 21))
    }

    @Test
    fun `decimal and arabic variants keep their own digits`() {
        assertEquals("03", numbers.format("decimalZero", 3))
        assertEquals("\uFF11\uFF12", numbers.format("decimalFullWidth", 12))
        assertEquals("\u0663-", numbers.format("arabic1Minus", 3))
        assertEquals("\u06F3-", numbers.format("arabic2Minus", 3))
        assertEquals("\u0969.", numbers.format("hindiNumPeriod", 3))
        assertEquals("\u0E53)", numbers.format("thaiNumParenR", 3))
    }

    @Test
    fun `east asian schemes count with units`() {
        assertEquals("三.", numbers.format("ea1ChsPeriod", 3))
        assertEquals("十一.", numbers.format("ea1ChsPeriod", 11))
        assertEquals("二十.", numbers.format("ea1ChsPeriod", 20))
        assertEquals("一千零五十.", numbers.format("ea1ChsPeriod", 1050))
        assertEquals("二千", numbers.format("chineseCountingThousand", 2))
        assertEquals("壹佰贰拾叁", numbers.format("chineseLegalSimplified", 123))
        assertEquals("다.", numbers.format("ea1JpnKorPeriod", 3))
    }

    @Test
    fun `korean and japanese schemes keep their own numerals`() {
        assertEquals("삼", numbers.format("koreanCounting", 3))
        assertEquals("십", numbers.format("koreanCounting", 10))
        assertEquals("삼", numbers.format("koreanDigital", 3))
        assertEquals("하나", numbers.format("koreanDigital2", 1))
        assertEquals("열하나", numbers.format("koreanDigital2", 11))
        assertEquals("壱", numbers.format("japaneseLegal", 1))
    }

    @Test
    fun `ideographic schemes pick the right glyph family`() {
        assertEquals("一二", numbers.format("ideographDigital", 12))
        assertEquals("甲", numbers.format("ideographTraditional", 1))
        assertEquals("甲", numbers.format("ideographTraditional", 11))
        assertEquals("子", numbers.format("ideographZodiac", 1))
        assertEquals("子", numbers.format("ideographZodiac", 13))
        assertEquals("豬", numbers.format("ideographZodiacTraditional", 12))
    }

    @Test
    fun `hebrew numerals avoid the divine name`() {
        assertEquals("ג-", numbers.format("hebrew2Minus", 3))
        assertEquals("טו-", numbers.format("hebrew2Minus", 15))
        assertEquals("טז-", numbers.format("hebrew2Minus", 16))
        assertEquals("כא-", numbers.format("hebrew2Minus", 21))
    }

    @Test
    fun `hindi and thai alpha schemes use letters`() {
        assertEquals("क.", numbers.format("hindiAlphaPeriod", 1))
        assertEquals("ख.", numbers.format("hindiAlpha1Period", 2))
        assertEquals("ก)", numbers.format("thaiAlphaParenR", 1))
    }

    @Test
    fun `the none scheme has no marker`() {
        assertNull(numbers.format("none", 1))
        assertNull(numbers.format("", 1))
    }

    @Test
    fun `counters restart per level when a shallower paragraph intervenes`() {
        val markers = numbers.markers(
            listOf(paragraph(0), paragraph(1), paragraph(1), paragraph(0), paragraph(1), paragraph(2), paragraph(1)),
        )

        assertEquals(listOf("1.", "1.", "2.", "2.", "1.", "1.", "2."), markers)
    }

    @Test
    fun `an unnumbered paragraph restarts its own level and the ones below`() {
        val markers = numbers.markers(
            listOf(paragraph(0), plain(0), paragraph(0), paragraph(1), plain(1), paragraph(1)),
        )

        assertEquals(listOf("1.", null, "1.", "1.", null, "1."), markers)
    }

    @Test
    fun `startAt sets the first number of its level`() {
        val markers = numbers.markers(
            listOf(paragraph(0, startAt = 5), paragraph(0), paragraph(1, startAt = 0), paragraph(1)),
        )

        assertEquals(listOf("5.", "6.", "0.", "1."), markers)
    }

    @Test
    fun `a none type counts as an unnumbered paragraph`() {
        val markers = numbers.markers(
            listOf(paragraph(0), paragraph(0, scheme = "none"), paragraph(0)),
        )

        assertEquals(listOf("1.", null, "1."), markers)
    }

    @Test
    fun `levels are clamped to the nine the formats define`() {
        val markers = numbers.markers(listOf(paragraph(0), paragraph(12)))

        assertEquals(listOf("1.", "1."), markers)
    }

    @Test
    fun `a body pass draws the number beside the paragraph's own runs`() {
        val paragraph = TextParagraph(
            properties = TextParagraphProperties(
                bullet = TextBullet.Numbering("arabicPeriod", null, red, TextBulletSize.Points(12.0)),
            ),
            runs = listOf(TextRun.Text("first", TextRunProperties(sizePt = 18.0))),
        )
        val layouts = BulletResolver().layouts(
            TextBody(
                paragraphs = listOf(
                    paragraph,
                    TextParagraph(
                        properties = paragraph.properties,
                        runs = listOf(TextRun.Text("second", TextRunProperties(sizePt = 18.0))),
                    ),
                ),
            ),
        )

        val first = layouts[0].marker as BulletMarker.Number
        assertEquals("1.", first.text)
        assertEquals(12.0, first.sizePt, 0.0)
        assertEquals(red, first.color)
        assertEquals("2.", (layouts[1].marker as BulletMarker.Number).text)
        assertEquals("first", (paragraph.runs.single() as TextRun.Text).text)
        assertEquals(layouts[0].metrics.markerRightEmu, layouts[0].metrics.textLeftEmu, 0.0)
    }

    private fun paragraph(level: Int = 0, startAt: Int? = null, scheme: String = "arabicPeriod"): TextParagraph =
        TextParagraph(
            level = level,
            properties = TextParagraphProperties(
                bullet = TextBullet.Numbering(scheme, startAt, null, TextBulletSize.Inherit),
            ),
        )

    private fun plain(level: Int): TextParagraph = TextParagraph(level = level)
}
