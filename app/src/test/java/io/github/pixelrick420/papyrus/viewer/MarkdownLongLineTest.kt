package io.github.pixelrick420.papyrus.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLongLineTest {

    private fun timed(label: String, block: () -> Unit) {
        val started = System.nanoTime()
        block()
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("$label took $millis ms", millis < LIMIT_MILLIS)
    }

    private val prose = "lorem ipsum dolor sit amet "

    @Test
    fun `a long line with no address in it is settled quickly`() {
        val line = prose.repeat(100_000)

        timed("no address") { assertTrue(Autolinks.find(line).isEmpty()) }
    }

    @Test
    fun `a long line of addresses finds every one of them`() {
        val line = "see https://example.org/page and www.example.com or mail me@example.com. ".repeat(20_000)

        timed("many addresses") {
            val links = Autolinks.find(line)

            assertEquals(60_000, links.size)
            assertTrue(links.zipWithNext().all { (a, b) -> a.end <= b.start })
        }
    }

    @Test
    fun `one address that fills the whole line is one link`() {
        val line = "https://example.org/" + "a".repeat(2_000_000)

        timed("one huge address") {
            val link = Autolinks.find(line).single()

            assertEquals(0, link.start)
            assertEquals(line.length, link.end)
        }
    }

    @Test
    fun `an at sign that is never an address does not slow the scan`() {
        val line = "@ ".repeat(500_000) + "a@".repeat(250_000)

        timed("stray at signs") { assertTrue(Autolinks.find(line).isEmpty()) }
    }

    @Test
    fun `many short addresses with a dotted host are all found`() {
        val line = "user@mail.example.co.uk ".repeat(40_000)

        timed("dotted hosts") { assertEquals(40_000, Autolinks.find(line).size) }
    }

    @Test
    fun `references with no definition leave a long line as it is`() {
        val line = "see [^a] and [^b] ".repeat(100_000)

        timed("references only") { assertTrue(MarkdownFootnotes.transform(line) == line) }
    }

    @Test
    fun `a long line of references is renumbered against a definition after it`() {
        val line = "word[^a] ".repeat(50_000)

        timed("references and a definition") {
            val out = MarkdownFootnotes.transform(line + "\n\n[^a]: the note")

            assertTrue(out.contains("the note"))
            assertFalse("a raw marker was left in the text", out.contains("[^a]"))
        }
    }

    @Test
    fun `brackets that never form a reference are left alone`() {
        val line = "[^".repeat(500_000)

        timed("open markers") { assertTrue(MarkdownFootnotes.transform(line) == line) }
    }

    @Test
    fun `a long line with no formula has no ranges`() {
        timed("no formula") { assertEquals(0, LatexSpanFactory(null, prose.repeat(100_000)).ranges.size) }
    }

    @Test
    fun `an opening delimiter that never closes is not a formula`() {
        timed("unterminated formula") { assertEquals(0, LatexSpanFactory(null, FENCE + "a".repeat(2_000_000)).ranges.size) }
    }

    @Test
    fun `a few thousand inline formulas on one line are all found, and none is a block`() {
        val line = "x ${FENCE}a$FENCE y ".repeat(2_000)

        timed("inline formulas") {
            val ranges = LatexSpanFactory(null, line).ranges

            assertEquals(2_000, ranges.size)
            assertTrue(ranges.none { it.isBlock })
            assertTrue(ranges.all { it.latex == "a" })
        }
    }

    @Test
    fun `a formula that is the whole of a very long line is inline`() {
        val line = FENCE + "x+".repeat(500_000) + "x" + FENCE

        timed("one huge formula") {
            val range = LatexSpanFactory(null, line).ranges.single()

            assertEquals(0, range.start)
            assertEquals(line.length, range.end)
            assertFalse(range.isBlock)
        }
    }

    private companion object {
        const val LIMIT_MILLIS = 10_000L

        /** The `$$` that opens and closes a formula, written so Kotlin does not read it as a template. */
        const val FENCE = "${'$'}${'$'}"
    }
}
