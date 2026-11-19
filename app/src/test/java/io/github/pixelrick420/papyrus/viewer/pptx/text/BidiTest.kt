package io.github.pixelrick420.papyrus.viewer.pptx.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BidiTest {

    @Test
    fun `latin text is one left-to-right run`() {
        val runs = Bidi.runs("abc", baseRtl = false)

        assertEquals(1, runs.size)
        assertEquals(0, runs.single().start)
        assertEquals(3, runs.single().end)
        assertFalse(runs.single().rtl)
    }

    @Test
    fun `hebrew text in a latin paragraph is one right-to-left run`() {
        val runs = Bidi.runs("אבג", baseRtl = false)

        assertEquals(1, runs.size)
        assertTrue(runs.single().rtl)
    }

    @Test
    fun `mixed text splits at the direction change`() {
        val text = "abc אבג"
        val runs = Bidi.runs(text, baseRtl = false)

        assertEquals("abc ", text.substring(runs.first().start, runs.first().end))
        assertFalse(runs.first().rtl)
        assertTrue(runs.any { it.rtl })
        assertEquals(text.length, runs.sumOf { it.end - it.start })
    }

    @Test
    fun `latin text in a right-to-left paragraph keeps an even level`() {
        val run = Bidi.runs("abc", baseRtl = true).single()

        assertFalse(run.rtl)
        assertEquals(2, run.level)
    }

    @Test
    fun `empty text has no runs`() {
        assertTrue(Bidi.runs("", baseRtl = false).isEmpty())
    }
}
