package com.papyrus.app.ui.viewer

import com.papyrus.app.ui.screens.FindState
import com.papyrus.app.viewer.OfficeBlock
import com.papyrus.app.viewer.OfficeCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hit computation and active-hit derivation, shared by all three viewer surfaces. */
class FindInFileTest {

    @Test
    fun `a blank query has no hits`() {
        // An empty query must match nothing, or the whole document paints as a match.
        assertEquals(emptyList<Int>(), findChunkHits(listOf("alpha", "beta"), ""))
        assertEquals(emptyList<Int>(), findChunkHits(listOf("alpha", "beta"), "   "))
    }

    @Test
    fun `matching is case-insensitive in both directions`() {
        assertEquals(listOf(0, 2), findChunkHits(listOf("Alpha", "beta", "ALPHA again"), "alpha"))
        assertEquals(listOf(1), findChunkHits(listOf("alpha", "beta"), "BETA"))
    }

    @Test
    fun `hits come back in document order`() {
        assertEquals(
            listOf(0, 1, 2),
            findChunkHits(listOf("one needle", "two needle", "three needle"), "needle"),
        )
    }

    @Test
    fun `every match inside one chunk is its own hit`() {
        // Regression: matches close together were counted as one, so the counter skipped them and the
        // whole cluster lit up as a single match. A chunk's index repeats once per match in it.
        assertEquals(listOf(0, 0, 0), findChunkHits(listOf("needle a needle b needle"), "needle"))
    }

    @Test
    fun `adjacent matches with nothing between them are still separate hits`() {
        assertEquals(listOf(0, 0, 0), findChunkHits(listOf("needleneedleneedle"), "needle"))
    }

    @Test
    fun `hits repeat per match and stay in document order across chunks`() {
        val chunks = listOf("needle needle", "nothing", "needle", "Needle NEEDLE needle")
        assertEquals(listOf(0, 0, 2, 3, 3, 3), findChunkHits(chunks, "needle"))
    }

    @Test
    fun `overlapping candidates are counted once, as the highlights are drawn`() {
        // "aa" in "aaaaa" paints two highlights, so it must count two hits, not four.
        assertEquals(listOf(0, 0), findChunkHits(listOf("aaaaa"), "aa"))
    }

    @Test
    fun `a query matching nothing yields an empty list rather than every index`() {
        assertEquals(emptyList<Int>(), findChunkHits(listOf("alpha", "beta"), "gamma"))
    }

    @Test
    fun `an empty document yields no hits`() {
        assertEquals(emptyList<Int>(), findChunkHits(emptyList(), "a"))
    }

    private fun blocks(): List<OfficeBlock> = listOf(
        OfficeBlock.Heading("Report", 1),
        OfficeBlock.Paragraph("first needle paragraph"),
        OfficeBlock.Table(
            listOf(
                listOf(OfficeCell("plain", 0), OfficeCell("needle in a cell", 1)),
                listOf(OfficeCell("other", 0)),
            ),
        ),
        OfficeBlock.Paragraph("no match here"),
    )

    @Test
    fun `headings paragraphs and table cells are all searched`() {
        assertEquals(listOf(1, 2), findBlockHits(blocks(), "needle"))
        assertEquals(listOf(0), findBlockHits(blocks(), "report"))
    }

    @Test
    fun `every match inside one block is its own hit`() {
        val blocks = listOf(
            OfficeBlock.Paragraph("needle needle"),
            OfficeBlock.Heading("no match", 2),
            OfficeBlock.Paragraph("a Needle, a NEEDLE and one more needle"),
        )
        assertEquals(listOf(0, 0, 2, 2, 2), findBlockHits(blocks, "needle"))
    }

    @Test
    fun `matches in separate cells and paragraphs of a table are counted one by one`() {
        val table = OfficeBlock.Table(
            listOf(
                listOf(OfficeCell("needle needle", 0), OfficeCell("none", 1)),
                listOf(OfficeCell("first needle\nsecond needle", 0), OfficeCell("needle", 1)),
            ),
        )
        // 2 in the first cell, 1 + 1 in the two paragraphs of the next, then 1 in the last: 5 in all.
        assertEquals(listOf(0, 0, 0, 0, 0), findBlockHits(listOf(table), "needle"))
    }

    @Test
    fun `a match is never counted across two pieces of text that are drawn separately`() {
        // Cells and a cell's paragraphs are separate Texts, so a query spanning them could be counted but
        // never highlighted, and the counter would step onto a match with nothing lit.
        val table = OfficeBlock.Table(
            listOf(listOf(OfficeCell("alpha", 0), OfficeCell("beta", 1)), listOf(OfficeCell("one\ntwo", 0))),
        )
        assertEquals(emptyList<Int>(), findBlockHits(listOf(table), "alpha beta"))
        assertEquals(emptyList<Int>(), findBlockHits(listOf(table), "one two"))
        assertEquals(listOf(0), findBlockHits(listOf(table), "alpha"))
    }

    @Test
    fun `the cell line split is what the renderer draws`() {
        assertEquals(listOf("a", "b"), officeCellLines(OfficeCell("a\n\nb\n", 0)))
        assertEquals(emptyList<String>(), officeCellLines(OfficeCell("", 0)))
    }

    @Test
    fun `a table is searched across its cells`() {
        val hit = findBlockHits(blocks(), "needle in a cell")
        assertEquals(listOf(2), hit)
    }

    @Test
    fun `images never match`() {
        // An image block has no text; a hit would jump the viewport to a picture.
        val withImage = listOf(OfficeBlock.Image(java.io.File("/tmp/needle.png"), "image/png"))
        assertEquals(emptyList<Int>(), findBlockHits(withImage, "needle"))
        assertEquals(emptyList<Int>(), findBlockHits(withImage, ""))
    }

    @Test
    fun `block matching is case-insensitive and skips blank queries`() {
        assertEquals(listOf(0), findBlockHits(blocks(), "REPORT"))
        assertEquals(emptyList<Int>(), findBlockHits(blocks(), "  "))
    }

    @Test
    fun `the active hit is the block a hit index points at`() {
        val state = FindState(open = true, query = "needle", hits = listOf(1, 4, 9), position = 1)

        assertEquals(4, state.activeIndex)
    }

    @Test
    fun `there is no active hit when the cursor is outside the hit list`() {
        // "No hits yet" and "cursor past the end" are both null, not index 0.
        assertNull(FindState().activeIndex)
        assertNull(FindState(hits = emptyList(), position = 0).activeIndex)
        assertNull(FindState(hits = listOf(2), position = 5).activeIndex)
        assertNull(FindState(hits = listOf(2), position = -1).activeIndex)
    }

    @Test
    fun `the first and last positions both resolve`() {
        assertEquals(2, FindState(hits = listOf(2, 3), position = 0).activeIndex)
        assertEquals(3, FindState(hits = listOf(2, 3), position = 1).activeIndex)
    }

    @Test
    fun `clearing the query clears the hits and the active index together`() {
        // A stale hit list would keep the counter lit after the box is emptied.
        val cleared = FindState().copy(query = "", hits = emptyList(), position = 0, occurrences = 0)

        assertNull(cleared.activeIndex)
        assertEquals(0, cleared.count)
        assertFalse(cleared.navigable)
    }

    @Test
    fun `occurrences are counted per match, not per chunk`() {
        // Markdown has no chunks, so the counter comes from raw occurrences.
        assertEquals(3, countOccurrences("alpha beta alpha gamma alpha", "alpha"))
        assertEquals(1, countOccurrences("alpha beta", "beta"))
        assertEquals(0, countOccurrences("alpha beta", "gamma"))
    }

    @Test
    fun `occurrences do not overlap`() {
        // "aa" in "aaaa" is two matches, not three: the page shows two highlights.
        assertEquals(2, countOccurrences("aaaa", "aa"))
        assertEquals(1, countOccurrences("aaaa", "aaaa"))
        // Five a's hold two whole "aa" matches; the fifth is a leftover, not a third.
        assertEquals(2, countOccurrences("aaaaa", "aa"))
    }

    @Test
    fun `occurrence counting is case-insensitive and blank-safe`() {
        assertEquals(3, countOccurrences("Alpha ALPHA alpha", "alpha"))
        assertEquals(0, countOccurrences("alpha", ""))
        assertEquals(0, countOccurrences("", "alpha"))
    }

    @Test
    fun `a query longer than the text is zero rather than an error`() {
        assertEquals(0, countOccurrences("ab", "abcdef"))
    }

    @Test
    fun `an in-place-only match set reports a count but cannot navigate`() {
        // Markdown has occurrences but no hit list; the bar reads both.
        val state = FindState(open = true, query = "alpha", hits = emptyList(), position = -1, occurrences = 7)

        assertEquals(7, state.count)
        assertFalse(state.navigable)
        assertNull(state.activeIndex)
    }

    @Test
    fun `navigable hits take precedence over a leftover occurrence count`() {
        // A format switch must not leave the old count beside the new hit list.
        val state = FindState(hits = listOf(0, 5), position = 0, occurrences = 99)

        assertEquals(2, state.count)
        assertTrue(state.navigable)
        assertEquals(0, state.activeIndex)
    }
}
