package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class TextDecodingTest {

    private fun bytes(vararg head: Int, text: String, charset: java.nio.charset.Charset) =
        ByteArray(head.size) { head[it].toByte() } + text.toByteArray(charset)

    @Test
    fun `plain utf-8 is decoded as is`() {
        assertEquals("# Title\nnaïve café", decodeText("# Title\nnaïve café".toByteArray()))
    }

    @Test
    fun `a utf-8 BOM is dropped so a leading heading still parses as one`() {
        val decoded = decodeText(bytes(0xEF, 0xBB, 0xBF, text = "# Title", charset = Charsets.UTF_8))

        assertEquals("# Title", decoded)
    }

    @Test
    fun `utf-16 little endian with BOM, as PowerShell writes it, is decoded`() {
        val decoded = decodeText(bytes(0xFF, 0xFE, text = "# Héllo\n| a | b |", charset = Charsets.UTF_16LE))

        assertEquals("# Héllo\n| a | b |", decoded)
    }

    @Test
    fun `utf-16 big endian with BOM is decoded`() {
        assertEquals("héllo", decodeText(bytes(0xFE, 0xFF, text = "héllo", charset = Charsets.UTF_16BE)))
    }

    @Test
    fun `empty and tiny inputs do not throw`() {
        assertEquals("", decodeText(ByteArray(0)))
        assertEquals("a", decodeText(byteArrayOf('a'.code.toByte())))
        assertEquals("", decodeText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())))
    }

    @Test
    fun `a lone 0xEF that is not a BOM is left to the utf-8 decoder`() {
        assertEquals("\uFFFD", decodeText(byteArrayOf(0xEF.toByte())))
    }
}
