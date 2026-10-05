package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ordering of a selection's ends, what it covers on each page, and which page a drag has reached. */
class PdfSelectionTest {

    private fun pos(page: Int, index: Int) = PdfTextPos(page, index)

    @Test
    fun `carets compare by page, then by position on it`() {
        assertTrue(pos(0, 90) < pos(1, 0))
        assertTrue(pos(2, 3) < pos(2, 4))
        assertEquals(0, pos(2, 3).compareTo(pos(2, 3)))
    }

    @Test
    fun `a selection is built in reading order from carets given either way round`() {
        val forward = requireNotNull(PdfSelection.between(pos(0, 2), pos(0, 8)))
        val backward = requireNotNull(PdfSelection.between(pos(0, 8), pos(0, 2)))

        assertEquals(forward, backward)
        assertEquals(pos(0, 2), forward.start)
        assertEquals(pos(0, 8), forward.end)
    }

    @Test
    fun `the same caret twice selects nothing`() {
        assertNull(PdfSelection.between(pos(3, 5), pos(3, 5)))
    }

    @Test
    fun `on one page a selection is its two carets`() {
        val selection = requireNotNull(PdfSelection.between(pos(4, 10), pos(4, 25)))

        assertEquals(PageSpan(10, 25), selection.spanOn(4))
        assertNull(selection.spanOn(3))
        assertNull(selection.spanOn(5))
    }

    @Test
    fun `across pages the first runs to its end, the last starts at its beginning, the middle is whole`() {
        val selection = requireNotNull(PdfSelection.between(pos(1, 40), pos(3, 7)))

        assertEquals(PageSpan(40, Int.MAX_VALUE), selection.spanOn(1))
        assertEquals(PageSpan(0, Int.MAX_VALUE), selection.spanOn(2))
        assertEquals(PageSpan(0, 7), selection.spanOn(3))
        assertNull(selection.spanOn(0))
        assertNull(selection.spanOn(4))
        assertEquals(1..3, selection.pages)
    }

    // Three pages 100 wide: heights 200, 100 and 300, 10 apart.
    private val geometry = PdfPageGeometry(
        pageCount = 3,
        widthPx = 100f,
        gapPx = 10f,
        aspectOf = { page -> floatArrayOf(2f, 1f, 3f)[page] },
    )

    @Test
    fun `a point on the page stays on it, in page units`() {
        val point = geometry.locate(0, 25f, 50f)

        assertEquals(0, point.page)
        assertEquals(0.25f, point.x, 0.0001f)
        assertEquals(0.25f, point.y, 0.0001f)
        assertEquals(2f, point.aspect, 0.0001f)
    }

    @Test
    fun `a drag below a page lands on the next one`() {
        // Page 1 starts 210 below page 0's top (200 high, then a gap of 10); 260 is 50 into it.
        val point = geometry.locate(0, 50f, 260f)

        assertEquals(1, point.page)
        assertEquals(0.5f, point.y, 0.0001f)
        assertEquals(1f, point.aspect, 0.0001f)
    }

    @Test
    fun `a drag can cross several pages in one move`() {
        // Page 2 starts 210 + 110 = 320 below page 0's top; 470 is 150 into its 300.
        val point = geometry.locate(0, 0f, 470f)

        assertEquals(2, point.page)
        assertEquals(0.5f, point.y, 0.0001f)
    }

    @Test
    fun `a drag above a page lands on the previous one`() {
        // 30 above page 1's top is 30 - 10 = 20 above page 0's bottom: 180 into its 200.
        val point = geometry.locate(1, 50f, -30f)

        assertEquals(0, point.page)
        assertEquals(0.9f, point.y, 0.0001f)
    }

    @Test
    fun `the gap between pages belongs to the page above`() {
        val point = geometry.locate(0, 50f, 205f)

        assertEquals(0, point.page)
        assertEquals(1f, point.y, 0.0001f)
    }

    @Test
    fun `past the first or last page it stops at that page's edge`() {
        assertEquals(0, geometry.locate(0, 50f, -500f).page)
        assertEquals(0f, geometry.locate(0, 50f, -500f).y, 0.0001f)
        assertEquals(2, geometry.locate(2, 50f, 9000f).page)
        assertEquals(1f, geometry.locate(2, 50f, 9000f).y, 0.0001f)
    }

    @Test
    fun `sideways is clamped to the page`() {
        assertEquals(0f, geometry.locate(1, -40f, 10f).x, 0.0001f)
        assertEquals(1f, geometry.locate(1, 400f, 10f).x, 0.0001f)
    }
}
