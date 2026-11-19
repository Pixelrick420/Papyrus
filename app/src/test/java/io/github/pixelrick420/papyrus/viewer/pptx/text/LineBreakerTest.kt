package io.github.pixelrick420.papyrus.viewer.pptx.text

import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LineBreakerTest {

    @Test
    fun `the provider's offsets become the allowed starts`() {
        val allowed = breaker(listOf(3)).breakBefore("aa bb", TextParagraphProperties())

        assertFalse(allowed[1])
        assertTrue(allowed[3])
        assertFalse(allowed[4])
    }

    @Test
    fun `a kinsoku line does not start with a closing mark`() {
        val allowed = breaker(listOf(1, 2)).breakBefore("あ。い", TextParagraphProperties())

        assertFalse(allowed[1])
        assertTrue(allowed[2])
    }

    @Test
    fun `hanging punctuation lets the closing mark overhang the line`() {
        val properties = TextParagraphProperties(hangingPunctuation = true)

        assertTrue(breaker(listOf(1)).breakBefore("あ。い", properties)[1])
    }

    @Test
    fun `an opening mark does not end a kinsoku line`() {
        val allowed = breaker(listOf(1)).breakBefore("「あ", TextParagraphProperties())

        assertFalse(allowed[1])
    }

    @Test
    fun `a no-break space is never a break`() {
        val allowed = breaker(listOf(3)).breakBefore("aa\u00A0bb", TextParagraphProperties())

        assertFalse(allowed[3])
    }

    @Test
    fun `eaLnBrk off suppresses a break between east Asian and latin text`() {
        val text = "aあ"
        val allowed = breaker(listOf(1)).breakBefore(
            text,
            TextParagraphProperties(eastAsianLineBreak = false),
        )

        assertFalse(allowed[1])
        assertTrue(breaker(listOf(1)).breakBefore(text, TextParagraphProperties())[1])
    }

    @Test
    fun `latinLnBrk off suppresses a break inside latin text`() {
        val text = "a-b"
        val suppressed = breaker(listOf(2)).breakBefore(
            text,
            TextParagraphProperties(latinLineBreak = false),
        )

        assertFalse(suppressed[2])
        assertTrue(breaker(listOf(2)).breakBefore(text, TextParagraphProperties())[2])
    }

    @Test
    fun `an offset the provider did not offer is never allowed`() {
        val allowed = breaker(emptyList()).breakBefore("aa bb", TextParagraphProperties())

        assertTrue(allowed.drop(1).none { it })
    }

    private fun breaker(offsets: List<Int>): LineBreaker =
        LineBreaker { text, _: Locale -> offsets.filter { it in 1 until text.length } }
}
