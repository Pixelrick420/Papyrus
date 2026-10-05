package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.BitSet

/** Match-to-rectangle mapping (which characters a match covers and where it splits) and selection (where a point lands, and what it picks up). */
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

    // Selecting: pointing at a page, finding the word under the finger, and getting its text back.

    /** Two lines, the second wrapped from the first at [wrapAt] (a space flagged as a break). */
    private fun wrapped(text: String, wrapAt: Int): PdfPageText {
        val boxes = text.mapIndexed { i, ch ->
            val second = i > wrapAt
            val top = if (second) 0.30f else 0.10f
            val left = (if (second) i - wrapAt - 1 else i) * 0.01f
            if (ch == ' ') floatArrayOf(Float.NaN, Float.NaN, Float.NaN, Float.NaN)
            else floatArrayOf(left, top, left + 0.01f, top + 0.02f)
        }
        return page(text, boxes, setOf(wrapAt))
    }

    @Test
    fun `the nearest character is the one under the point`() {
        val text = "hello world"
        val hit = requireNotNull(page(text, line(text)).nearestChar(0.065f, 0.11f))

        assertEquals(6, hit.index) // the "w"
        assertEquals(0f, hit.distance, 0.0001f)
    }

    @Test
    fun `a point off the text picks the nearest character and says how far`() {
        val text = "hello world"
        val page = page(text, line(text))

        // Straight below the "w": the gap is the vertical distance and nothing else.
        val below = requireNotNull(page.nearestChar(0.065f, 0.16f))
        assertEquals(6, below.index)
        assertEquals(0.04f, below.distance, 0.0001f)

        // Past the end of the line, level with it: the last letter, by the sideways gap.
        val right = requireNotNull(page.nearestChar(0.50f, 0.11f))
        assertEquals(10, right.index)
        assertEquals(0.39f, right.distance, 0.0001f)
    }

    @Test
    fun `a gap between words never answers with the space`() {
        val text = "ab cd"
        val page = page(text, line(text))

        // Over the space at index 2, nearer the "b" than the "c".
        assertEquals(1, page.nearestChar(0.0235f, 0.11f)?.index)
        assertEquals(3, page.nearestChar(0.0305f, 0.11f)?.index)
    }

    @Test
    fun `the nearer of two lines wins`() {
        val text = "two lines of a long paragraph"
        val page = wrapped(text, wrapAt = text.indexOf("a long") + 1)

        // First line at 0.10..0.12, second at 0.30..0.32.
        assertTrue(requireNotNull(page.nearestChar(0.005f, 0.14f)).index <= text.indexOf("a long"))
        assertTrue(requireNotNull(page.nearestChar(0.005f, 0.27f)).index > text.indexOf("a long"))
    }

    @Test
    fun `distance is in page heights whatever the page's shape`() {
        val text = "hello"
        val page = page(text, line(text))

        // 0.04 past the last box across a page twice as tall as wide: 0.02 of its height.
        assertEquals(0.02f, requireNotNull(page.nearestChar(0.09f, 0.11f, aspect = 2f)).distance, 0.0001f)
        // On a page wider than tall the same step is a larger share of the height.
        assertEquals(0.08f, requireNotNull(page.nearestChar(0.09f, 0.11f, aspect = 0.5f)).distance, 0.0001f)
    }

    @Test
    fun `a page without boxes has nothing to point at`() {
        val page = PdfPageText("find the needle", FloatArray(0))

        assertNull(page.nearestChar(0.5f, 0.5f))
        assertNull(page.caretAt(0.5f, 0.5f))
        assertFalse(page.hasBoxes)
        assertFalse(page.isSelectable)
        assertTrue(page.selectionRects(0, 4).isEmpty())
    }

    @Test
    fun `a blank page is not selectable even with boxes`() {
        val text = "   "
        val page = page(text, line(text))

        assertTrue(page.hasBoxes)
        assertFalse(page.isSelectable)
        assertTrue(page("hello", line("hello")).isSelectable)
    }

    @Test
    fun `the caret goes before or after a character by which half was touched`() {
        val text = "hello"
        val page = page(text, line(text))

        assertEquals(2, page.caretAt(0.022f, 0.11f)) // left half of the "l" at 0.02..0.03
        assertEquals(3, page.caretAt(0.028f, 0.11f)) // right half
    }

    @Test
    fun `a caret off either end of a line stays on the line`() {
        val text = "hello"
        val page = page(text, line(text))

        assertEquals(0, page.caretAt(-0.30f, 0.11f))
        assertEquals(5, page.caretAt(0.90f, 0.11f))
    }

    @Test
    fun `a word is the run of letters under the finger`() {
        val text = "hello world"
        val page = page(text, line(text))

        assertEquals(0..4, page.wordAround(1))
        assertEquals(0..4, page.wordAround(0))
        assertEquals(0..4, page.wordAround(4))
        assertEquals(6..10, page.wordAround(10))
    }

    @Test
    fun `punctuation stays out of the word but an apostrophe stays in`() {
        val text = "don't stop."
        val page = page(text, line(text))

        assertEquals(0..4, page.wordAround(2)) // don't
        assertEquals(6..9, page.wordAround(7)) // stop, without its full stop
    }

    @Test
    fun `a space or an index off the page is no word`() {
        val text = "ab cd"
        val page = page(text, line(text))

        assertNull(page.wordAround(2))
        assertNull(page.wordAround(-1))
        assertNull(page.wordAround(5))
    }

    @Test
    fun `a selection is one rectangle across the gap between its words`() {
        val text = "hello world"
        val rects = page(text, line(text)).selectionRects(0, 11)

        assertEquals(1, rects.size)
        assertEquals(0.00f, rects[0].left, 0.0001f)
        assertEquals(0.11f, rects[0].right, 0.0001f)
    }

    @Test
    fun `a selection covers only the characters asked for`() {
        val text = "hello world"
        val rects = page(text, line(text)).selectionRects(6, 9) // "wor"

        assertEquals(1, rects.size)
        assertEquals(0.06f, rects[0].left, 0.0001f)
        assertEquals(0.09f, rects[0].right, 0.0001f)
    }

    @Test
    fun `a selection that wraps is a rectangle per line`() {
        val text = "two lines of a long paragraph"
        val wrapAt = text.indexOf("a long") + 1
        val rects = wrapped(text, wrapAt).selectionRects(text.indexOf("of"), text.length)

        assertEquals(2, rects.size)
        assertEquals(0.10f, rects[0].top, 0.0001f)
        assertEquals(0.30f, rects[1].top, 0.0001f)
    }

    @Test
    fun `an end past the page means to the end and an empty range draws nothing`() {
        val text = "hello world"
        val page = page(text, line(text))

        assertEquals(0.11f, page.selectionRects(6, Int.MAX_VALUE)[0].right, 0.0001f)
        assertEquals(0.00f, page.selectionRects(-5, 3)[0].left, 0.0001f)
        assertTrue(page.selectionRects(4, 4).isEmpty())
        assertTrue(page.selectionRects(9, 2).isEmpty())
        assertTrue(page.selectionRects(40, 50).isEmpty())
    }

    @Test
    fun `selected text is the characters between the carets`() {
        val text = "hello world"
        val page = page(text, line(text))

        assertEquals("llo wo", page.textBetween(2, 8))
        assertEquals("hello world", page.textBetween(0, Int.MAX_VALUE))
        assertEquals("", page.textBetween(5, 5))
        assertEquals("", page.textBetween(8, 3))
    }

    @Test
    fun `a wrap point is copied as a newline`() {
        val text = "two lines of a long paragraph"
        val wrapAt = text.indexOf("a long") + 1
        val copied = wrapped(text, wrapAt).textBetween(text.indexOf("of"), text.length)

        assertEquals("of a\nlong paragraph", copied)
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
