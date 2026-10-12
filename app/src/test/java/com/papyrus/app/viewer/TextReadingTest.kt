package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.Charset

class TextReadingTest {

    /** One byte per read, so every buffer boundary in the reader and the sample splice is crossed. */
    private class Trickle(private val data: ByteArray) : InputStream() {
        private var at = 0
        override fun read(): Int = if (at < data.size) data[at++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            val one = read()
            if (one < 0) return -1
            b[off] = one.toByte()
            return 1
        }
    }

    private fun chunks(text: String, maxBytes: Int = Int.MAX_VALUE, perChunk: Int = TEXT_LINES_PER_CHUNK) =
        readTextChunks(ByteArrayInputStream(text.toByteArray()), maxBytes, linesPerChunk = perChunk)

    private fun lines(count: Int) = (1..count).joinToString("\n") { "line $it" }

    @Test
    fun `lines are grouped forty to a chunk and rejoined with newlines`() {
        val result = chunks(lines(100))

        assertEquals(3, result.chunks.size)
        assertEquals(40, result.chunks[0].lines().size)
        assertEquals(40, result.chunks[1].lines().size)
        assertEquals(20, result.chunks[2].lines().size)
        assertEquals(lines(100), result.chunks.joinToString("\n"))
        assertEquals(100, result.lineCount)
    }

    @Test
    fun `the chunks match what lineSequence and chunked produced before`() {
        for (count in listOf(0, 1, 39, 40, 41, 80, 81)) {
            for (suffix in listOf("", "\n")) {
                val text = if (count == 0) suffix else lines(count) + suffix
                val old = text.lineSequence().chunked(40).map { it.joinToString("\n") }.toList()
                val new = chunks(text).chunks

                // The one deliberate difference: no empty chunk after a final newline that closes a full chunk.
                val expected = if (old.size > 1 && old.last().isEmpty()) old.dropLast(1) else old
                assertEquals("lines=$count suffix=${suffix.length}", expected, new)
            }
        }
    }

    @Test
    fun `carriage returns end lines whether alone or paired, even across a read boundary`() {
        val text = "a\r\nb\rc\nd\r\n\r\ne"
        val expected = listOf("a", "b", "c", "d", "", "e").joinToString("\n")

        val whole = readTextChunks(ByteArrayInputStream(text.toByteArray()), Int.MAX_VALUE)
        val trickled = readTextChunks(Trickle(text.toByteArray()), Int.MAX_VALUE)

        assertEquals(listOf(expected), whole.chunks)
        assertEquals(listOf(expected), trickled.chunks)
        assertEquals(6, whole.lineCount)
    }

    @Test
    fun `an empty file is one empty chunk and one line`() {
        val result = chunks("")

        assertEquals(listOf(""), result.chunks)
        assertEquals(1, result.lineCount)
        assertEquals(0, result.maxLineColumns)
    }

    @Test
    fun `a trailing newline keeps its empty last line`() {
        assertEquals(listOf("a\nb\n"), chunks("a\nb\n").chunks)
        assertEquals(3, chunks("a\nb\n").lineCount)
    }

    @Test
    fun `the widest line is measured in columns, with wide characters double and a tab four`() {
        assertEquals(6, chunks("ab\n日本語\n\tx").maxLineColumns)
        assertEquals(10, chunks("0123456789").maxLineColumns)
    }

    @Test
    fun `reading stops at the limit and says the file was cut short`() {
        val cut = chunks("0123456789abcdefghij", maxBytes = 10)

        assertEquals(listOf("0123456789"), cut.chunks)
        assertTrue(cut.truncated)
    }

    @Test
    fun `a file exactly at the limit is not truncated`() {
        val exact = chunks("0123456789", maxBytes = 10)

        assertEquals(listOf("0123456789"), exact.chunks)
        assertFalse(exact.truncated)
    }

    @Test
    fun `a limit that lands inside a character leaves no replacement character behind`() {
        // "éé" is four bytes; ten bytes of é is five characters, so nine bytes cut the fifth in half.
        val cut = readTextChunks(ByteArrayInputStream("é".repeat(10).toByteArray()), maxBytes = 9)

        assertEquals("é".repeat(4), cut.chunks.single())
        assertTrue(cut.truncated)
    }

    @Test
    fun `windows-1252 text is decoded as such without being asked`() {
        val data = "Café – “quoted”".toByteArray(TextEncodings.WINDOWS_1252)

        val result = readTextChunks(ByteArrayInputStream(data), Int.MAX_VALUE)

        assertEquals("Café – “quoted”", result.chunks.single())
        assertEquals(TextEncodings.WINDOWS_1252, result.encoding.charset)
    }

    @Test
    fun `utf-16 with and without a BOM is decoded and the BOM is not in the text`() {
        val withBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "# Héllo\nsecond".toByteArray(Charsets.UTF_16LE)
        val without = "Hello there\nsecond line".toByteArray(Charsets.UTF_16BE)

        assertEquals("# Héllo\nsecond", readTextChunks(ByteArrayInputStream(withBom), Int.MAX_VALUE).chunks.single())
        assertEquals("Hello there\nsecond line", readTextChunks(ByteArrayInputStream(without), Int.MAX_VALUE).chunks.single())
    }

    @Test
    fun `a utf-8 BOM is dropped`() {
        val data = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "# Title".toByteArray()

        assertEquals("# Title", readTextString(ByteArrayInputStream(data), Int.MAX_VALUE).text)
        assertEquals("# Title", readTextChunks(ByteArrayInputStream(data), Int.MAX_VALUE).chunks.single())
    }

    @Test
    fun `a multi-byte character straddling the end of the detection sample does not spoil utf-8`() {
        // 'a' pushes every é across the 64 KB sample boundary at an odd offset.
        val text = "a" + "é".repeat(100_000)

        val result = readTextChunks(ByteArrayInputStream(text.toByteArray()), Int.MAX_VALUE)

        assertEquals(Charsets.UTF_8, result.encoding.charset)
        assertEquals(text, result.chunks.single())
    }

    @Test
    fun `a forced charset wins over detection`() {
        val data = "naïve".toByteArray()

        val asLatin = readTextChunks(ByteArrayInputStream(data), Int.MAX_VALUE, forced = Charsets.ISO_8859_1)

        assertEquals("naÃ¯ve", asLatin.chunks.single())
        assertEquals(EncodingSource.MANUAL, asLatin.encoding.source)
    }

    @Test
    fun `a forced charset that does not own the BOM leaves it visible`() {
        val data = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "x".toByteArray()

        val forced = readTextChunks(ByteArrayInputStream(data), Int.MAX_VALUE, forced = TextEncodings.WINDOWS_1252)

        assertEquals("ï»¿x", forced.chunks.single())
    }

    @Test
    fun `the whole-string reader agrees with the chunk reader`() {
        val text = lines(95)

        val string = readTextString(ByteArrayInputStream(text.toByteArray()), Int.MAX_VALUE)

        assertEquals(text, string.text)
        assertEquals(text, chunks(text).chunks.joinToString("\n"))
    }

    @Test
    fun `the source is opened once`() {
        var opens = 0
        val source = object : InputStream() {
            private val inner = ByteArrayInputStream("a\nb".toByteArray())
            init { opens++ }
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, len)
        }

        readTextChunks(source, Int.MAX_VALUE)

        assertEquals(1, opens)
    }

    @Test
    fun `a guesser is consulted for non-utf-8 bytes and its answer decodes the file`() {
        val sjis = Charset.forName("Shift_JIS")
        val data = "こんにちは\n世界".toByteArray(sjis)

        val result = readTextChunks(ByteArrayInputStream(data), Int.MAX_VALUE, guesser = CharsetGuesser { _, _ -> "Shift_JIS" })

        assertEquals("こんにちは\n世界", result.chunks.single())
    }
}
