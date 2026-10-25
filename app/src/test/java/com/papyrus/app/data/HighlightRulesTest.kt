package com.papyrus.app.data

import com.papyrus.app.viewer.NormRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightRulesTest {

    /** A line of text: 0.02 tall, [top] down the page. */
    private fun line(left: Float, right: Float, top: Float) = NormRect(left, top, right, top + 0.02f)

    private fun highlight(id: Long, page: Int, vararg rects: NormRect) = Highlight(id, page, rects.toList())

    @Test
    fun `boxes survive being stored and read back unchanged`() {
        val boxes = listOf(NormRect(0.1f, 0.25f, 0.9f, 0.27f), NormRect(0.1234567f, 0.3f, 0.5f, 0.32f))

        assertEquals(boxes, decodeRects(encodeRects(boxes)))
    }

    @Test
    fun `no boxes is the empty string and back`() {
        assertEquals("", encodeRects(emptyList()))
        assertEquals(emptyList<NormRect>(), decodeRects(""))
    }

    @Test
    fun `a damaged entry is dropped and the others are kept`() {
        val decoded = decodeRects("0.1,0.2,0.3,0.4;oops;0.1,0.2,0.3;0.5,0.6,0.7,0.8")

        assertEquals(listOf(NormRect(0.1f, 0.2f, 0.3f, 0.4f), NormRect(0.5f, 0.6f, 0.7f, 0.8f)), decoded)
    }

    @Test
    fun `marks are grouped by page in their stored order`() {
        val a = line(0.1f, 0.5f, 0.1f)
        val b = line(0.1f, 0.5f, 0.3f)
        val c = line(0.2f, 0.6f, 0.2f)

        val byPage = listOf(highlight(1, 0, a), highlight(2, 4, c), highlight(3, 0, b)).marksByPage()

        assertEquals(
            mapOf(0 to listOf(highlight(1, 0, a), highlight(3, 0, b)), 4 to listOf(highlight(2, 4, c))),
            byPage,
        )
    }

    @Test
    fun `only plain highlights are exportable and a note is left out`() {
        val marked = line(0.1f, 0.5f, 0.1f)
        val noted = line(0.1f, 0.5f, 0.3f)
        val marks = listOf(
            highlight(1, 0, marked),
            Highlight(2, 0, listOf(noted), HighlightKind.NOTE, "check this"),
        )

        assertEquals(mapOf(0 to listOf(listOf(marked))), marks.exportableRectsByPage())
    }

    @Test
    fun `a document whose marks all carry notes exports nothing`() {
        val note = Highlight(1, 3, listOf(line(0.1f, 0.5f, 0.1f)), HighlightKind.NOTE, "hm")

        assertTrue(listOf(note).exportableRectsByPage().isEmpty())
    }

    @Test
    fun `an unknown stored kind reads as a highlight`() {
        assertEquals(HighlightKind.HIGHLIGHT, HighlightKind.fromCode(1))
        assertEquals(HighlightKind.NOTE, HighlightKind.fromCode(2))
        assertEquals(HighlightKind.HIGHLIGHT, HighlightKind.fromCode(0))
        assertEquals(HighlightKind.HIGHLIGHT, HighlightKind.fromCode(99))
    }

    @Test
    fun `a note is trimmed, capped and blank when the reader cleared it`() {
        assertEquals("keep", cleanHighlightNote("  keep  "))
        assertEquals(null, cleanHighlightNote("   "))
        assertEquals(MAX_HIGHLIGHT_NOTE_CHARS, cleanHighlightNote("x".repeat(MAX_HIGHLIGHT_NOTE_CHARS + 50))?.length)
        assertEquals("a", cleanHighlightNote(" a "))
    }

    @Test
    fun `a selection inside a highlight touches it`() {
        val stored = highlight(1, 2, line(0.1f, 0.8f, 0.4f))

        assertEquals(listOf(stored), listOf(stored).touchedBy(mapOf(2 to listOf(line(0.3f, 0.4f, 0.4f)))))
    }

    @Test
    fun `a selection that only overlaps the end of a highlight still touches it`() {
        val stored = highlight(1, 0, line(0.1f, 0.4f, 0.4f))

        assertEquals(1, listOf(stored).touchedBy(mapOf(0 to listOf(line(0.35f, 0.7f, 0.4f)))).size)
    }

    @Test
    fun `the next word on the same line does not touch a highlighted one`() {
        val stored = highlight(1, 0, line(0.1f, 0.3f, 0.4f))

        assertTrue(listOf(stored).touchedBy(mapOf(0 to listOf(line(0.32f, 0.5f, 0.4f)))).isEmpty())
    }

    @Test
    fun `the line below a highlight does not touch it even where the glyph boxes overlap`() {
        val stored = highlight(1, 0, NormRect(0.1f, 0.40f, 0.9f, 0.43f))
        // Starts above the highlight's bottom edge, as tight leading makes line boxes do.
        val below = NormRect(0.2f, 0.425f, 0.6f, 0.455f)

        assertTrue(listOf(stored).touchedBy(mapOf(0 to listOf(below))).isEmpty())
    }

    @Test
    fun `the same place on another page does not touch it`() {
        val stored = highlight(1, 0, line(0.1f, 0.8f, 0.4f))

        assertTrue(listOf(stored).touchedBy(mapOf(1 to listOf(line(0.1f, 0.8f, 0.4f)))).isEmpty())
    }

    @Test
    fun `every highlight a wrapped selection crosses is touched and the ones it misses are not`() {
        val first = highlight(1, 0, line(0.1f, 0.9f, 0.10f))
        val second = highlight(2, 0, line(0.1f, 0.9f, 0.20f))
        val missed = highlight(3, 0, line(0.1f, 0.9f, 0.50f))
        val selection = mapOf(0 to listOf(line(0.5f, 0.9f, 0.10f), line(0.1f, 0.9f, 0.20f)))

        assertEquals(listOf(first, second), listOf(first, second, missed).touchedBy(selection))
    }
}
