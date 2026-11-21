package io.github.pixelrick420.papyrus.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure: the length cap, its cut points, and the source offsets of the resulting sub-units. */
class TtsUnitSplitterTest {

    @Test
    fun `text under the cap is one range and leaves short units alone`() {
        val text = "A short paragraph. With two sentences."
        assertEquals(listOf(text.indices), TtsUnitSplitter.split(text))
        assertEquals(listOf("A short paragraph."), TtsDocumentSource.paragraphs("A short paragraph.").map { it.text })
    }

    @Test
    fun `a long single line splits at sentence boundaries into units within the cap`() {
        val text = "The quick brown fox jumps over the lazy dog. ".repeat(130)
        val ranges = TtsUnitSplitter.split(text)

        assertTrue("got ${ranges.size} ranges", ranges.size > 1)
        assertTrue(ranges.all { it.last - it.first + 1 <= TtsUnitSplitter.MAX_UNIT_CHARS })
        assertTrue(ranges.dropLast(1).all { text.substring(it).trimEnd().endsWith(".") })
        assertEquals(text, ranges.joinToString("") { text.substring(it) })
    }

    @Test
    fun `one long sentence with spaces splits at whitespace`() {
        val text = List(600) { "word$it" }.joinToString(" ")
        val ranges = TtsUnitSplitter.split(text, max = 100)

        assertTrue(ranges.size > 1)
        assertTrue(ranges.all { it.last - it.first + 1 <= 100 })
        assertTrue(ranges.dropLast(1).all { text[it.last] == ' ' })
        assertEquals(text, ranges.joinToString("") { text.substring(it) })
    }

    @Test
    fun `a run with no whitespace cuts at the cap`() {
        val text = "x".repeat(9_000)
        val ranges = TtsUnitSplitter.split(text, max = 100)

        assertEquals(90, ranges.size)
        assertEquals(0 until 100, ranges.first())
        assertEquals(8_900 until 9_000, ranges.last())
    }

    @Test
    fun `a cut never falls between a high and a low surrogate`() {
        val max = 20
        val emoji = "\uD83D\uDE00"
        val text = "a".repeat(max - 1) + emoji + "b".repeat(60)
        val ranges = TtsUnitSplitter.split(text, max = max)

        assertEquals(0 until max - 1, ranges.first())
        assertEquals(max - 1, ranges[1].first)
        assertEquals(emoji, text.substring(ranges[1].first, ranges[1].first + 2))
    }

    @Test
    fun `a sub-unit start round-trips through the source with CRLF and indentation`() {
        val line = "Alpha bravo charlie delta echo foxtrot."
        val sources = listOf(
            List(150) { "   $line" }.joinToString("\r\n"),
            List(150) { "\t\t$line" }.joinToString("\n"),
        )
        for (source in sources) {
            val units = TtsDocumentSource.unitsFromPlainText(listOf(source))
            assertTrue("${units.size} units", units.size > 1)
            for (unit in units) {
                val at = unit.text.indexOf("bravo")
                assertTrue(unit.text, at >= 0)
                val mapped = TtsAlignment.sourceRange(source, unit.text, unit.start, at until at + 5)!!
                assertEquals("bravo", source.substring(mapped))
            }
        }
    }

    @Test
    fun `lazy chunk counts match the sub-units its get yields`() {
        val chunk = "Alpha bravo charlie foxtrot. ".repeat(200)
        val lazy = ChunkTtsUnits.of(listOf(chunk))
        val eager = TtsDocumentSource.unitsFromPlainText(listOf(chunk))

        assertTrue(lazy.size > 1)
        assertEquals(eager.size, lazy.size)
        assertEquals(lazy.size, lazy.toList().size)
        assertEquals(eager, lazy.toList())
    }
}
