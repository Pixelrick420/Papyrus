package com.papyrus.app.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which matches are orange and which are yellow, and how a block's current match is split across its pieces. */
class FindHighlightTest {

    private fun colours(text: String, highlight: FindHighlight?) =
        highlightedText(text, highlight).spanStyles.map { it.item.background }

    private fun starts(text: String, highlight: FindHighlight?) =
        highlightedText(text, highlight).spanStyles.map { it.start }

    @Test
    fun `with no current match every match is yellow`() {
        val text = "needle needle needle"
        assertEquals(List(3) { OtherMatchColor }, colours(text, FindHighlight("needle", null)))
    }

    @Test
    fun `only the current match is orange and the rest are yellow`() {
        // Regression: every match in the active chunk used to be orange, so a cluster read as one match.
        val text = "needle needle needle"
        assertEquals(
            listOf(OtherMatchColor, ActiveMatchColor, OtherMatchColor),
            colours(text, FindHighlight("needle", 1)),
        )
    }

    @Test
    fun `the current match is exactly one and is the one asked for`() {
        val text = "needleneedleneedle"
        for (active in 0..2) {
            val colours = colours(text, FindHighlight("needle", active))
            assertEquals(1, colours.count { it == ActiveMatchColor })
            assertEquals(ActiveMatchColor, colours[active])
        }
    }

    @Test
    fun `a current match beyond this text's matches leaves everything yellow`() {
        assertEquals(List(2) { OtherMatchColor }, colours("needle needle", FindHighlight("needle", 7)))
    }

    @Test
    fun `spans cover the matches and nothing else, and never overlap`() {
        val text = "xx needle xx needle"
        val spans = highlightedText(text, FindHighlight("needle", 0)).spanStyles
        assertEquals(listOf(3 to 9, 13 to 19), spans.map { it.start to it.end })
    }

    @Test
    fun `matched text is drawn in a fixed dark colour`() {
        val spans = highlightedText("needle", FindHighlight("needle", 0)).spanStyles
        assertEquals(MatchTextColor, spans[0].item.color)
    }

    @Test
    fun `no highlight and no match both leave the text unstyled`() {
        assertTrue(highlightedText("needle", null).spanStyles.isEmpty())
        assertTrue(highlightedText("nothing here", FindHighlight("needle", 0)).spanStyles.isEmpty())
        assertEquals("needle", highlightedText("needle", null).text)
    }

    @Test
    fun `a blank query makes no highlight`() {
        assertNull(findHighlightFor("", 0))
        assertNull(findHighlightFor("   ", null))
        assertEquals(FindHighlight("a", 2), findHighlightFor("a", 2))
    }

    // A table's current match is numbered across all its cells; each cell subtracts what came before.

    @Test
    fun `skipping shifts the current match into this piece's own numbering`() {
        assertEquals(FindHighlight("q", 1), FindHighlight("q", 4).skipping(3))
        assertEquals(FindHighlight("q", 0), FindHighlight("q", 3).skipping(3))
    }

    @Test
    fun `a piece after the current match has none`() {
        // The current match is the 2nd of the block; a piece that starts at the 5th has none of it.
        assertNull(FindHighlight("q", 1).skipping(4).activeOccurrence)
        assertNull(FindHighlight("q", null).skipping(2).activeOccurrence)
    }

    @Test
    fun `the current match lands in exactly one of a block's pieces`() {
        // Three cells holding 2, 1 and 3 matches. Whichever match is current, exactly one cell paints it.
        val cells = listOf("q q", "q", "q q q")
        for (current in 0 until 6) {
            var before = 0
            val painted = cells.map { cell ->
                val h = FindHighlight("q", current).skipping(before)
                before += countOccurrences(cell, "q")
                h.activeRange(cell)
            }
            assertEquals("match $current", 1, painted.count { it != null })
        }
    }

    @Test
    fun `the active range is the current match's characters`() {
        assertEquals(7..12, FindHighlight("needle", 1).activeRange("needle needle"))
        assertNull(FindHighlight("needle", 2).activeRange("needle needle"))
        assertNull(FindHighlight("needle", null).activeRange("needle needle"))
    }
}
