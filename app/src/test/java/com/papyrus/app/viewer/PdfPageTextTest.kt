package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.BitSet

/** Match-to-rectangle mapping: which characters a match covers and where it splits. */
class PdfPageTextTest {

    /** One line of characters charWidth apart; spaces get no box, as the extractor emits. */
    private fun line(text: String, top: Float = 0.10f, bottom: Float = 0.12f, charWidth: Float = 0.01f) =
        text.mapIndexed { i, ch ->
            if (ch == ' ') floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN)
            else floatArrayOf(i * charWidth, top, (i + 1) * charWidth, bottom)
        }

    private fun page(text: String, boxes: List<FloatArray>, breaks: Set<Int> = emptySet()) =
        PdfPageText(
            text,
            boxes.flatMap { it.toList() }.toFloatArray(),
            BitSet().apply { breaks.forEach { set(it) } },
        )

    @Test
    fun `a match is one rectangle spanning its characters`() {
        val text = "find the needle here"
        val rects = page(text, line(text)).matchRects("needle")

        assertEquals(1, rects.size)
        assertEquals(0.09f, rects[0].left, 0.0001f)
        assertEquals(0.15f, rects[0].right, 0.0001f)
        assertEquals(0.10f, rects[0].top, 0.0001f)
        assertEquals(0.12f, rects[0].bottom, 0.0001f)
    }

    @Test
    fun `every occurrence gets its own rectangle`() {
        val text = "needle and needle and needle"
        assertEquals(3, page(text, line(text)).matchRects("needle").size)
    }

    @Test
    fun `a phrase spanning words is one rectangle across the gap`() {
        // The space between the words has no box; the rectangle must still run across it.
        val text = "a quick brown fox"
        val rects = page(text, line(text)).matchRects("quick brown")

        assertEquals(1, rects.size)
        assertEquals(0.02f, rects[0].left, 0.0001f)
        assertEquals(0.13f, rects[0].right, 0.0001f)
    }

    @Test
    fun `a match that wraps is one rectangle per line`() {
        // The wrap space is flagged as a line break, so the words between lines are not painted.
        val text = "two lines of a long paragraph"
        val wrapAt = text.indexOf("a long") + 1 // the space after "a"
        val boxes = text.mapIndexed { i, ch ->
            val second = i > wrapAt
            val top = if (second) 0.30f else 0.10f
            val left = (if (second) i - wrapAt - 1 else i) * 0.01f
            if (ch == ' ') floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN)
            else floatArrayOf(left, top, left + 0.01f, top + 0.02f)
        }
        val rects = page(text, boxes, setOf(wrapAt)).matchRects("lines of a long paragraph")

        assertEquals(2, rects.size)
        assertEquals(0.10f, rects[0].top, 0.0001f)
        assertEquals(0.30f, rects[1].top, 0.0001f)
    }

    @Test
    fun `wrapping works the same when the lines run sideways`() {
        // Rotated a quarter turn, the next line is across, so only the recorded break splits it.
        val text = "ab cd"
        val boxes = listOf(
            floatArrayOf(0.10f, 0.10f, 0.12f, 0.11f),
            floatArrayOf(0.10f, 0.11f, 0.12f, 0.12f),
            floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN),
            floatArrayOf(0.20f, 0.10f, 0.22f, 0.11f),
            floatArrayOf(0.20f, 0.11f, 0.22f, 0.12f),
        )
        val rects = page(text, boxes, setOf(2)).matchRects("ab cd")

        assertEquals(2, rects.size)
        assertEquals(0.12f, rects[0].right, 0.0001f)
        assertEquals(0.20f, rects[1].left, 0.0001f)
    }

    @Test
    fun `matching is case-insensitive`() {
        val text = "Needle NEEDLE"
        assertEquals(2, page(text, line(text)).matchRects("needle").size)
    }

    @Test
    fun `a page with no boxes is still found but has nothing to draw`() {
        // Over the memory budget: searchable, not highlightable, so contains and matchRects differ.
        val page = PdfPageText("find the needle", FloatArray(0))

        assertTrue(page.contains("needle"))
        assertTrue(page.matchRects("needle").isEmpty())
    }

    @Test
    fun `an absent or blank query matches nothing`() {
        val text = "alpha beta"
        val page = page(text, line(text))

        assertFalse(page.contains("gamma"))
        assertTrue(page.matchRects("gamma").isEmpty())
        assertFalse(page.contains(""))
        assertTrue(page.matchRects("").isEmpty())
    }

    @Test
    fun `the rectangle count is capped`() {
        val text = "a ".repeat(50)
        assertEquals(10, page(text, line(text)).matchRects("a", limit = 10).size)
    }

    @Test
    fun `a typed fi finds a ligature and extra spaces collapse`() {
        // The extractor normalises glyphs to NFKC, so the page text holds "fi"; the query gets the
        // same treatment so what the reader types and what the page holds meet in the middle.
        assertEquals("fi", PdfPageText.normalizeQuery("\uFB01"))
        assertEquals("a b c", PdfPageText.normalizeQuery("a   b \n c"))
        val text = "a fine day"
        assertTrue(page(text, line(text)).contains("fi"))
        assertTrue(page(text, line(text)).contains("a  fine"))
    }

    // Rectangles are grouped per match: a wrapped match is several rectangles but one match, so the
    // find bar's count (matches) and its colouring (which rectangles are orange) can both be right.

    @Test
    fun `each match is its own group`() {
        val text = "needle and needle and needle"
        val groups = page(text, line(text)).matchGroups("needle")

        assertEquals(3, groups.size)
        assertTrue(groups.all { it.size == 1 })
        assertEquals(0.00f, groups[0][0].left, 0.0001f)
        assertEquals(0.11f, groups[1][0].left, 0.0001f)
        assertEquals(0.22f, groups[2][0].left, 0.0001f)
    }

    @Test
    fun `matches that touch stay separate groups`() {
        // The reported bug at the geometry level: close matches must not merge into one rectangle.
        val text = "needleneedle needle"
        val groups = page(text, line(text)).matchGroups("needle")

        assertEquals(3, groups.size)
        assertEquals(0.06f, groups[0][0].right, 0.0001f)
        assertEquals(0.06f, groups[1][0].left, 0.0001f)
    }

    @Test
    fun `a match that wraps is one group of two rectangles`() {
        val text = "two lines of a long paragraph and a long tail"
        val wrapAt = text.indexOf("a long paragraph") + 1
        val boxes = text.mapIndexed { i, ch ->
            val second = i > wrapAt
            val top = if (second) 0.30f else 0.10f
            val left = (if (second) i - wrapAt - 1 else i) * 0.01f
            if (ch == ' ') floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN)
            else floatArrayOf(left, top, left + 0.01f, top + 0.02f)
        }
        val groups = page(text, boxes, setOf(wrapAt)).matchGroups("a long paragraph")

        assertEquals(1, groups.size)
        assertEquals(2, groups[0].size)
    }

    @Test
    fun `the group count equals the match count`() {
        val text = "aa aa aa aaaa"
        val page = page(text, line(text))
        assertEquals(page.countMatches("aa"), page.matchGroups("aa").size)
        assertEquals(5, page.countMatches("aa"))
    }

    @Test
    fun `counting does not overlap matches`() {
        // "aa" in "aaaaa" paints two highlights, so it is two matches.
        assertEquals(2, PdfPageText("aaaaa", FloatArray(0)).countMatches("aa"))
    }

    @Test
    fun `a page past the box budget still counts every match but has no groups`() {
        // Searchable but not highlightable: the count feeds the find bar, the groups feed the page.
        val page = PdfPageText("needle needle needle", FloatArray(0))

        assertEquals(3, page.countMatches("needle"))
        assertTrue(page.matchGroups("needle").isEmpty())
    }

    @Test
    fun `counting follows the same normalising as matching`() {
        val text = "a fine day, fine"
        val page = page(text, line(text))
        assertEquals(2, page.countMatches("\uFB01ne"))
        assertEquals(2, page.matchGroups("\uFB01ne").size)
        assertEquals(0, page.countMatches(""))
    }

    @Test
    fun `a match with no drawable glyph keeps its place so groups stay aligned with matches`() {
        // The middle match has no boxes at all. Dropping its entry would make the third match's
        // rectangles read as the second's, and the wrong word would turn orange.
        val text = "ab ab ab"
        val boxes = text.mapIndexed { i, ch ->
            if (ch == ' ' || i in 3..4) floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN)
            else floatArrayOf(i * 0.01f, 0.1f, (i + 1) * 0.01f, 0.12f)
        }
        val groups = page(text, boxes).matchGroups("ab")

        assertEquals(3, groups.size)
        assertTrue(groups[1].isEmpty())
        assertEquals(0.06f, groups[2][0].left, 0.0001f)
    }

    @Test
    fun `the limit cuts off later matches but never splits the order`() {
        val text = "a ".repeat(50)
        val groups = page(text, line(text)).matchGroups("a", limit = 10)

        assertEquals(10, groups.size)
        assertEquals(50, page(text, line(text)).countMatches("a"))
    }

    @Test
    fun `boxes must line up one to one with the text`() {
        var failed = false
        try {
            PdfPageText("abc", FloatArray(8))
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue("a mismatched box array should be rejected, not silently misaligned", failed)
    }
}
