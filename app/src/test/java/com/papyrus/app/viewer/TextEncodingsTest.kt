package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class TextEncodingsTest {

    private fun bytes(vararg head: Int, text: String = "", charset: Charset = Charsets.UTF_8) =
        ByteArray(head.size) { head[it].toByte() } + text.toByteArray(charset)

    private fun detect(data: ByteArray, truncated: Boolean = false, guesser: CharsetGuesser? = null) =
        TextEncodings.detect(data, data.size, truncated, guesser)

    @Test
    fun `a utf-8 BOM is recognised and its length reported so it is never left in the text`() {
        val found = detect(bytes(0xEF, 0xBB, 0xBF, text = "# Title"))

        assertEquals(Charsets.UTF_8, found.charset)
        assertEquals(3, found.bomLength)
        assertEquals(EncodingSource.BOM, found.source)
    }

    @Test
    fun `utf-16 BOMs in both byte orders are recognised`() {
        val little = detect(bytes(0xFF, 0xFE, text = "héllo", charset = Charsets.UTF_16LE))
        val big = detect(bytes(0xFE, 0xFF, text = "héllo", charset = Charsets.UTF_16BE))

        assertEquals(Charsets.UTF_16LE, little.charset)
        assertEquals(Charsets.UTF_16BE, big.charset)
        assertEquals(2, little.bomLength)
        assertEquals(2, big.bomLength)
    }

    @Test
    fun `a utf-32 little-endian BOM is not mistaken for utf-16 followed by a NUL`() {
        val found = detect(bytes(0xFF, 0xFE, 0x00, 0x00, text = "a", charset = Charset.forName("UTF-32LE")))

        assertEquals("UTF-32LE", found.charset.name())
        assertEquals(4, found.bomLength)
    }

    @Test
    fun `plain ascii is utf-8 and empty input does not throw`() {
        assertEquals(EncodingSource.ASCII, detect("hello\n".toByteArray()).source)
        assertEquals(Charsets.UTF_8, detect(ByteArray(0)).charset)
    }

    @Test
    fun `valid utf-8 with multi-byte characters is utf-8`() {
        val found = detect("naïve café — 日本語 😀".toByteArray())

        assertEquals(Charsets.UTF_8, found.charset)
        assertEquals(EncodingSource.UTF8, found.source)
    }

    @Test
    fun `bytes that are not utf-8 fall back to windows-1252, which decodes every byte`() {
        // "café – “quoted”" in Windows-1252: é is E9 (invalid as UTF-8), the dash and quotes are in 0x80-0x9F.
        val data = "café – “quoted”".toByteArray(TextEncodings.WINDOWS_1252)

        val found = detect(data)

        assertEquals(TextEncodings.WINDOWS_1252, found.charset)
        assertEquals(EncodingSource.FALLBACK, found.source)
        assertEquals("café – “quoted”", String(data, found.charset))
    }

    @Test
    fun `a lone lead byte is not utf-8`() {
        assertEquals(TextEncodings.WINDOWS_1252, detect(byteArrayOf(0xEF.toByte())).charset)
    }

    @Test
    fun `a character cut by the end of a truncated sample does not count against utf-8`() {
        val cut = "abc".toByteArray() + byteArrayOf(0xC3.toByte())

        assertEquals(Charsets.UTF_8, detect(cut, truncated = true).charset)
        // The same bytes as a whole file end mid-character, which is damage, not UTF-8.
        assertEquals(TextEncodings.WINDOWS_1252, detect(cut, truncated = false).charset)
    }

    @Test
    fun `utf-16 without a BOM is found from the zero bytes beside ascii`() {
        val little = detect("Hello, world\nsecond line\n".toByteArray(Charsets.UTF_16LE))
        val big = detect("Hello, world\nsecond line\n".toByteArray(Charsets.UTF_16BE))

        assertEquals(Charsets.UTF_16LE, little.charset)
        assertEquals(Charsets.UTF_16BE, big.charset)
        assertEquals(EncodingSource.UTF16_PATTERN, little.source)
    }

    @Test
    fun `zeros spread evenly through binary-looking data are not utf-16`() {
        val data = ByteArray(400) { if (it % 3 == 0) 0 else 'x'.code.toByte() }

        assertTrue(detect(data).source != EncodingSource.UTF16_PATTERN)
    }

    @Test
    fun `the guesser is asked only when the bytes are not utf-8`() {
        var asked = 0
        val guesser = CharsetGuesser { _, _ -> asked++; "Shift_JIS" }

        detect("plain".toByteArray(), guesser = guesser)
        detect("naïve".toByteArray(), guesser = guesser)

        assertEquals(0, asked)
    }

    @Test
    fun `a guess for non-utf-8 bytes is used`() {
        val data = "こんにちは".toByteArray(Charset.forName("Shift_JIS"))

        val found = detect(data, guesser = CharsetGuesser { _, _ -> "Shift_JIS" })

        assertEquals("Shift_JIS", found.charset.name())
        assertEquals(EncodingSource.GUESSED, found.source)
        assertEquals("こんにちは", String(data, found.charset))
    }

    @Test
    fun `a latin-1 guess becomes windows-1252, its superset`() {
        val found = detect("café".toByteArray(Charsets.ISO_8859_1), guesser = CharsetGuesser { _, _ -> "ISO-8859-1" })

        assertEquals(TextEncodings.WINDOWS_1252, found.charset)
    }

    @Test
    fun `an unusable guess is ignored rather than trusted`() {
        val data = "café".toByteArray(Charsets.ISO_8859_1)

        for (name in listOf("UTF-8", "UTF-16LE", "no-such-charset", "IBM037", "")) {
            val found = detect(data, guesser = CharsetGuesser { _, _ -> name })
            assertEquals("guess $name", TextEncodings.WINDOWS_1252, found.charset)
        }
        assertEquals(TextEncodings.WINDOWS_1252, detect(data, guesser = CharsetGuesser { _, _ -> null }).charset)
    }

    @Test
    fun `a manually chosen charset skips a BOM only when it is that charset's own`() {
        val utf8Bom = bytes(0xEF, 0xBB, 0xBF, text = "x")
        val utf16Bom = bytes(0xFF, 0xFE, text = "x", charset = Charsets.UTF_16LE)

        assertEquals(3, TextEncodings.bomLengthFor(Charsets.UTF_8, utf8Bom, utf8Bom.size))
        assertEquals(0, TextEncodings.bomLengthFor(TextEncodings.WINDOWS_1252, utf8Bom, utf8Bom.size))
        assertEquals(2, TextEncodings.bomLengthFor(Charsets.UTF_16LE, utf16Bom, utf16Bom.size))
        assertEquals(0, TextEncodings.bomLengthFor(Charsets.UTF_16BE, utf16Bom, utf16Bom.size))
        // FF FE 00 00 is UTF-32LE's mark, but a person who picked UTF-16LE still wants the FF FE gone.
        val ambiguous = bytes(0xFF, 0xFE, 0x00, 0x00)
        assertEquals(2, TextEncodings.bomLengthFor(Charsets.UTF_16LE, ambiguous, ambiguous.size))
    }

    @Test
    fun `utf-8 validation is strict`() {
        fun valid(vararg b: Int) = TextEncodings.isValidUtf8(ByteArray(b.size) { b[it].toByte() }, b.size)

        assertTrue(valid(0xF0, 0x9F, 0x98, 0x80)) // U+1F600
        assertFalse("overlong NUL", valid(0xC0, 0x80))
        assertFalse("overlong slash", valid(0xE0, 0x80, 0xAF))
        assertFalse("surrogate", valid(0xED, 0xA0, 0x80))
        assertFalse("past U+10FFFF", valid(0xF4, 0x90, 0x80, 0x80))
        assertFalse("never a lead byte", valid(0xF5, 0x80, 0x80, 0x80))
        assertFalse("stray continuation", valid(0x80))
        assertFalse("bad second continuation", valid(0xE2, 0x82, 0x28))
    }

    @Test
    fun `charset lookup returns null for a name nothing supports`() {
        assertNull(TextEncodings.charsetOrNull("definitely-not-a-charset"))
        assertEquals(Charsets.UTF_8, TextEncodings.charsetOrNull("UTF-8"))
    }

    @Test
    fun `every choice offered by the picker resolves to a charset`() {
        assertTrue(TextEncodings.choices.isNotEmpty())
        assertTrue(TextEncodings.choices.all { TextEncodings.charsetOrNull(it.charsetName) != null })
        assertEquals("UTF-8", TextEncodings.choices.first().charsetName)
    }
}
