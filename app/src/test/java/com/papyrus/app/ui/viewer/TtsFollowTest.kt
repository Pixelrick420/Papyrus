package com.papyrus.app.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure: when the page follows the voice, and by how much. */
class TtsFollowTest {

    private val viewport = 1000f
    private val margin = 80f
    private val line = 40f

    private fun delta(lineTop: Float, lineHeight: Float = line, height: Float = viewport): Float {
        val window = followWindow(lineHeight, height, margin)
        return followScrollDelta(lineTop, window, height)
    }

    @Test
    fun `the window keeps room above and the rest of the zone below`() {
        val window = followWindow(line, viewport, margin)
        assertEquals(80f, window.above, 0.001f)
        // The line may sit at 40% of the viewport, so 60% of it is wanted from the line's top down.
        assertEquals(600f, window.below, 0.001f)
    }

    @Test
    fun `a short viewport gives up room above before it gives up the line`() {
        val window = followWindow(line, 100f, margin)
        assertEquals(25f, window.above, 0.001f)
        assertTrue(window.above + window.below <= 100f)
    }

    @Test
    fun `an unmeasured viewport asks for nothing but the line`() {
        val window = followWindow(line, 0f, margin)
        assertEquals(0f, window.above, 0.001f)
        assertEquals(line, window.below, 0.001f)
    }

    @Test
    fun `a line inside the zone does not move the page`() {
        assertEquals(0f, delta(lineTop = 80f), 0.001f)
        assertEquals(0f, delta(lineTop = 250f), 0.001f)
        assertEquals(0f, delta(lineTop = 400f), 0.001f)
    }

    @Test
    fun `a line past the zone scrolls by exactly the overshoot`() {
        // 40 px below the zone's lowest place: one line's worth of scroll, not a page.
        assertEquals(40f, delta(lineTop = 440f), 0.001f)
        // Reading on, line by line, the line is held where it was brought to.
        assertEquals(40f, delta(lineTop = 440f), 0.001f)
        assertEquals(0f, delta(lineTop = 400f), 0.001f)
    }

    @Test
    fun `a line below the viewport is brought up to the lowest place of the zone`() {
        val to = 1800f
        val moved = delta(lineTop = to)
        assertEquals(400f, to - moved, 0.001f)
    }

    @Test
    fun `a line above the top margin scrolls back by the shortfall`() {
        // Behind the top margin by 30 px.
        assertEquals(-30f, delta(lineTop = 50f), 0.001f)
        // Above the viewport altogether: it ends at the top margin.
        val moved = delta(lineTop = -900f)
        assertEquals(80f, -900f - moved, 0.001f)
    }

    @Test
    fun `a block is followed by its top`() {
        val window = followWindow(0f, viewport, margin)
        assertEquals(0f, followScrollDelta(300f, window, viewport), 0.001f)
        assertEquals(60f, followScrollDelta(460f, window, viewport), 0.001f)
    }
}
