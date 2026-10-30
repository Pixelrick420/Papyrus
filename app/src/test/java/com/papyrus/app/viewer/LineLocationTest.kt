package com.papyrus.app.viewer

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LineLocationTest {

    private fun locate(line: Int, lines: Int) = locateLine(line, lines)!!

    @Test
    fun `the first line is the top of the first chunk`() {
        val target = locate(1, 1_000)

        assertEquals(0, target.chunk)
        assertEquals(0, target.lineInChunk)
        assertEquals(0f, target.fraction, 0f)
    }

    @Test
    fun `a chunk holds forty lines, so the forty-first is the top of the second`() {
        assertEquals(0, locate(40, 1_000).chunk)
        assertEquals(39, locate(40, 1_000).lineInChunk)
        assertEquals(1, locate(41, 1_000).chunk)
        assertEquals(0, locate(41, 1_000).lineInChunk)
        assertEquals(0f, locate(41, 1_000).fraction, 0f)
        assertEquals(24, locate(1_000, 1_000).chunk)
    }

    @Test
    fun `the last line is in the last chunk, measured by that chunk's own lines`() {
        // 100 lines: chunks of 40, 40 and 20. Line 100 is the 20th of the last, so 19/20 of the way down it.
        val target = locate(100, 100)

        assertEquals(2, target.chunk)
        assertEquals(19, target.lineInChunk)
        assertEquals(0.95f, target.fraction, 1e-6f)
        // The first line of that chunk is still at its top.
        assertEquals(0f, locate(81, 100).fraction, 0f)
    }

    @Test
    fun `the last line of a file that fills its chunks is at the bottom of the last`() {
        val target = locate(80, 80)

        assertEquals(1, target.chunk)
        assertEquals(39, target.lineInChunk)
        assertEquals(0.975f, target.fraction, 1e-6f)
    }

    @Test
    fun `a file of one line has one line to go to`() {
        val target = locate(1, 1)

        assertEquals(0, target.chunk)
        assertEquals(0f, target.fraction, 0f)
        assertNull(locateLine(2, 1))
    }

    @Test
    fun `no line is at zero, below it, past the end, or in a file without lines`() {
        assertNull(locateLine(0, 100))
        assertNull(locateLine(-1, 100))
        assertNull(locateLine(Int.MIN_VALUE, 100))
        assertNull(locateLine(101, 100))
        assertNull(locateLine(Int.MAX_VALUE, 100))
        assertNull(locateLine(1, 0))
        assertNull(locateLine(1, -5))
    }

    @Test
    fun `a line is where the chunker put it, for every line of files of every shape`() {
        for (lines in listOf(1, 2, 39, 40, 41, 79, 80, 81, 119, 120, 121, 333)) for (ending in listOf("", "\n", "\r\n")) {
            val text = (1..lines).joinToString("\n") { "line $it" } + ending
            val read = readTextChunks(ByteArrayInputStream(text.toByteArray()), Int.MAX_VALUE, forced = Charsets.UTF_8)
            val numbered = numberedLines(read.lineCount, endsWithNewline = ending.isNotEmpty())

            assertEquals("lines $lines", lines, numbered)
            for (line in 1..lines) {
                val target = locate(line, numbered)
                assertEquals("line $line of $lines", "line $line", read.chunks[target.chunk].split('\n')[target.lineInChunk])
            }
            assertNull(locateLine(lines + 1, numbered))
        }
    }

    @Test
    fun `the fraction reads back as the same line the way a saved position is read`() {
        // A saved position is turned back into a line as fraction * 40, rounded down, so it must not land a line early.
        // Only whole chunks are checked: the last, shorter one is measured by its own lines.
        for (lines in listOf(40, 80, 400)) for (line in 1..lines) {
            val target = locate(line, lines)

            assertEquals("line $line of $lines", target.lineInChunk, (target.fraction * TEXT_LINES_PER_CHUNK).toInt())
        }
    }

    @Test
    fun `a gutter does not number the empty line after the last newline`() {
        assertEquals(3, numberedLines(lineCount = 4, endsWithNewline = true))
        assertEquals(4, numberedLines(lineCount = 4, endsWithNewline = false))
        // An empty file is one empty line; a file of just a newline is one line too, not none.
        assertEquals(1, numberedLines(lineCount = 1, endsWithNewline = false))
        assertEquals(1, numberedLines(lineCount = 2, endsWithNewline = true))
        assertEquals(1, numberedLines(lineCount = 1, endsWithNewline = true))
    }

    @Test
    fun `what the dialog accepts is exactly what can be located`() {
        val lines = 150
        for (input in listOf("1", "75", "150", "0", "151", "-1", "", "7.5", "1,000", "999999999999")) {
            val line = parseGoToNumber(input, lines)

            if (line == null) continue
            assertNotNull(input, locateLine(line, lines))
        }
        assertEquals(null, parseGoToNumber("151", lines)?.let { locateLine(it, lines) })
    }
}
