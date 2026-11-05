package com.papyrus.app.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderWidthTest {

    @Test
    fun `a page within the budget is rendered at the width it is shown at`() {
        assertEquals(1080, renderWidthFor(1080, 1.414f, 10_000_000, 1080))
        assertEquals(2160, renderWidthFor(2160, 1.414f, 10_000_000, 1080))
    }

    @Test
    fun `a zoomed page is held to the pixel budget`() {
        val width = renderWidthFor(5400, 1.5f, 6_000_000, 1080)

        assertEquals(2000, width)
        assertTrue(width * (width * 1.5f) <= 6_000_000f)
    }

    @Test
    fun `the page at 100 percent is never rendered below its own width`() {
        // A budget this small would give 840, but the unzoomed page has to stay sharp.
        assertEquals(1080, renderWidthFor(3000, 1.414f, 1_000_000, 1080))
    }

    @Test
    fun `zooming out renders at the shown width, not the 100 percent floor`() {
        assertEquals(800, renderWidthFor(800, 1.414f, 1_000_000, 1080))
    }

    @Test
    fun `a taller page gets a narrower render for the same budget`() {
        val square = renderWidthFor(4000, 1f, 4_000_000, 1000)
        val tall = renderWidthFor(4000, 4f, 4_000_000, 1000)

        assertEquals(2000, square)
        assertEquals(1000, tall)
    }

    @Test
    fun `a page with no usable aspect or budget is rendered as shown`() {
        assertEquals(1500, renderWidthFor(1500, 0f, 4_000_000, 1000))
        assertEquals(1500, renderWidthFor(1500, 1.4f, 0, 1000))
    }
}
