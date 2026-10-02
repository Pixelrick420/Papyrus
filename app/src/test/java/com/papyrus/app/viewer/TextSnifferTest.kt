package com.papyrus.app.viewer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** Both directions: a false positive shows mojibake, a false negative blocks the file. */
class TextSnifferTest {

    private fun sniff(bytes: ByteArray, length: Int = bytes.size) = TextSniffer.looksLikeText(bytes, length)

    private fun sniff(text: String) = sniff(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `plain ascii and utf-8 are text`() {
        assertTrue(sniff("hello world\n"))
        assertTrue(sniff("Grüße aus München — ünïcodé ✓\n"))
    }

    @Test
    fun `whitespace control characters do not count against the ratio`() {
        // Newlines, tabs and CRs are what a text file is made of, mostly indentation.
        assertTrue(sniff("def f():\n\treturn 1\r\n\n"))
    }

    @Test
    fun `a BOM-prefixed utf-8 file is text`() {
        assertTrue(sniff(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "hello".toByteArray()))
    }

    @Test
    fun `an empty file is not text`() {
        // Nothing to render; a blank viewer is a worse answer than "empty".
        assertFalse(sniff(ByteArray(0)))
    }

    @Test
    fun `the control-byte ratio is compared inclusively`() {
        // Exactly 2% control bytes is still text, one byte over is not.
        val atLimit = "a".repeat(980) + "\u0001".repeat(20)
        assertTrue(sniff(atLimit))

        val overLimit = "a".repeat(979) + "\u0001".repeat(21)
        assertFalse(sniff(overLimit))
    }

    @Test
    fun `a mostly-binary control run is rejected`() {
        assertFalse(sniff("a".repeat(50) + "\u0001".repeat(50)))
    }

    @Test
    fun `a NUL byte means binary`() {
        // The classic case: a truncated UTF-16 or a binary padded with zeros.
        assertFalse(sniff(byteArrayOf(0x41, 0x00, 0x42, 0x00)))
    }

    @Test
    fun `known container magic wins even when the rest decodes as utf-8`() {
        // A ZIP of ASCII entry names is valid UTF-8, so the magic table is checked first.
        assertFalse(sniff(byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "word/document.xml".toByteArray()))
        assertFalse(sniff("%PDF-1.7\n%\u00E2\u00E3\u00CF\u00D3\n".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(sniff(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertFalse(sniff("GIF89a.....".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `every archived-binary magic in the table is caught`() {
        // Guards the table: a signed byte written as a positive int (0xFD, 0x89) stops matching.
        assertFalse(sniff(byteArrayOf(0x50, 0x4B, 0x05, 0x06)))
        assertFalse(sniff(byteArrayOf(0x50, 0x4B, 0x07, 0x08)))
        assertFalse(sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertFalse(sniff("RIFF\u0000WEBP".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(sniff(byteArrayOf(0x1F, 0x8B.toByte(), 0x08)))
        assertFalse(sniff(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())))
        assertFalse(sniff(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())))
        assertFalse(sniff("dex\n035\u0000".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(sniff("SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(sniff(byteArrayOf(0x00, 0x61, 0x73, 0x6D)))
        assertFalse(sniff("Rar!\u001A\u0007".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(sniff(byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0xFC.toByte())))
        assertFalse(sniff("BZh91AY&SY".toByteArray(Charsets.ISO_8859_1)))
        // 0xFD is > 0x7F, so it matches only as a signed Byte, not as a positive int.
        assertFalse(sniff(byteArrayOf(0xFD.toByte(), '7'.code.toByte(), 'z'.code.toByte(), 'X'.code.toByte(), 'Z'.code.toByte(), 0x00)))
    }

    @Test
    fun `malformed utf-8 is rejected rather than replaced`() {
        // A lone continuation byte and a truncated three-byte sequence, both common in binaries.
        assertFalse(sniff(byteArrayOf(0xC3.toByte(), 0x28)))
        assertFalse(sniff(byteArrayOf(0xE2.toByte(), 0x82.toByte())))
        // A valid prefix followed by invalid bytes: the whole buffer fails, not just the tail.
        assertFalse(sniff("good text ".toByteArray() + byteArrayOf(0xFF.toByte(), 0xFE.toByte())))
    }

    @Test
    fun `utf-16 text is not mistaken for utf-8 text`() {
        // Little-endian UTF-16 of "hi", a NUL after every ASCII byte, so both checks fire.
        assertFalse(sniff("hi".toByteArray(Charsets.UTF_16LE)))
    }

    @Test
    fun `only the supplied prefix is examined`() {
        // The array doubles as a read buffer, so bytes past length must not reach the verdict.
        val buffer = "text".toByteArray(Charsets.UTF_8).copyOf(16)
        buffer[8] = 0
        buffer[9] = 0x50
        buffer[10] = 0x4B

        assertTrue(sniff(buffer, length = 4))
    }

    @Test
    fun `the stream overload reads at most the sniff window`() {
        val long = "a".repeat(TextSniffer.MAX_SNIFF_BYTES * 4).toByteArray()
        assertTrue(TextSniffer.looksLikeText(ByteArrayInputStream(long)))

        // A NUL past the window is fine; scanning on would read 200 MB to draw one line.
        val tailBinary = "a".repeat(TextSniffer.MAX_SNIFF_BYTES + 1).toByteArray() + byteArrayOf(0x00)
        assertTrue(TextSniffer.looksLikeText(ByteArrayInputStream(tailBinary)))

        // The same NUL inside the window does disqualify the file.
        val headBinary = "a".repeat(TextSniffer.MAX_SNIFF_BYTES - 1).toByteArray() + byteArrayOf(0x00)
        assertFalse(TextSniffer.looksLikeText(ByteArrayInputStream(headBinary)))
    }
}
