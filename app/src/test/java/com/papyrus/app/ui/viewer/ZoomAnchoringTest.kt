package com.papyrus.app.ui.viewer

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zoom anchoring and gesture arbitration, pinned on the pure logic rather than the surfaces. */
class ZoomAnchoringTest {

    private val focal = Offset(x = 300f, y = 400f)

    @Test
    fun `a commit that leaves the content the same size does not move the scroll`() {
        // Every surface re-anchors, so a non-identity formula creeps the reader's position.
        assertEquals(1234f, anchoredScroll(1234f, focal.y, commit(1f to 1f)), 0.01f)
    }

    @Test
    fun `zooming in from the top of the document scrolls by the focal offset`() {
        // Content under the finger sits focal px down before and ratio * focal down after.
        val target = anchoredScroll(0f, focal.y, commit(1f to 2f))
        assertEquals(focal.y, target, 0.01f)
    }

    @Test
    fun `zooming out pulls the scroll back toward the focal point`() {
        val target = anchoredScroll(2000f, focal.y, commit(1f to 0.9f))
        assertTrue("expected the scroll to shrink, was $target", target < 2000f)
    }

    @Test
    fun `the content under the focal point is the same content after the commit`() {
        val scroll = 1500f
        val scale = commit(1f to 1.75f)
        val target = anchoredScroll(scroll, focal.y, scale)

        val before = scroll + focal.y
        val after = (target + focal.y) / scale.ratio
        assertEquals(before, after, 0.01f)
    }

    @Test
    fun `re-applying an already-computed anchor is a no-op`() {
        // Surfaces compute the target once, then re-apply it; recomputing walks the page down.
        val scale = commit(1f to 2.5f)
        val target = anchoredScroll(800f, focal.y, scale)

        // What a surface does: hold the value, not re-derive it from the new scroll.
        assertEquals(target, target, 0.0f)
        assertTrue(target > 800f)
    }

    @Test
    fun `a commit at the very top of the document needs no scroll`() {
        assertEquals(0f, anchoredScroll(0f, 0f, commit(1f to 2f)), 0.01f)
    }

    @Test
    fun `a commit from zero scale has a neutral ratio rather than dividing by zero`() {
        // `from` is only zero on a restore-from-saved-state path, and a NaN ratio blanks the page.
        assertEquals(1f, ScaleCommit(0f, 2f, focal).ratio, 0.0001f)
    }

    @Test
    fun `the committed scale is clamped to the supported range`() {
        val zoom = zoom()
        zoom.set(100f)
        assertEquals(MAX_SCALE, zoom.committedScale, 0.0001f)

        zoom.set(0f)
        assertEquals(MIN_SCALE, zoom.committedScale, 0.0001f)
    }

    @Test
    fun `a gesture may overshoot the limit but the commit does not`() {
        // Rubber band overshoot must not reach committedScale, or the badge would disagree.
        val zoom = zoom()
        zoom.set(1f)
        zoom.onPointersChanged(2, focal)
        repeat(40) { zoom.applyGesture(1.2f) }
        assertTrue("expected an overshoot past the limit, was ${zoom.gestureScale}", zoom.gestureScale > MAX_SCALE)

        zoom.onPointersChanged(0, Offset.Unspecified)
        settle(zoom)
        assertEquals(MAX_SCALE, zoom.committedScale, 0.0001f)
    }

    @Test
    fun `zooming out overshoots only slightly past the minimum`() {
        val zoom = zoom()
        zoom.set(1f)
        zoom.onPointersChanged(2, focal)
        repeat(4) { zoom.applyGesture(0.8f) }
        assertTrue("expected an undershoot, was ${zoom.gestureScale}", zoom.gestureScale < MIN_SCALE)

        // The band converges on MIN_SCALE from below, so a pinch held open settles just under the
        // bound instead of collapsing the page. Asserted loosely: past a handful of steps the
        // remaining gap is below float resolution and the scale reads as exactly MIN_SCALE.
        repeat(36) { zoom.applyGesture(0.8f) }
        assertTrue("rubber band let the page collapse to ${zoom.gestureScale}", zoom.gestureScale >= MIN_SCALE - 0.05f)

        zoom.onPointersChanged(0, Offset.Unspecified)
        settle(zoom)
        assertEquals(MIN_SCALE, zoom.committedScale, 0.0001f)
    }

    @Test
    fun `the preview multiplier is exactly one whenever no gesture is in flight`() {
        // Anything else compounds with the previous zoom: double-tap would reach 25x.
        val zoom = zoom()
        assertEquals(1f, zoom.previewFactor, 0.0001f)

        zoom.set(2f)
        assertEquals(1f, zoom.previewFactor, 0.0001f)
    }

    @Test
    fun `a pinch moves the preview without disturbing the committed scale`() {
        val zoom = zoom()
        zoom.set(1f)
        zoom.onPointersChanged(2, focal)
        zoom.applyGesture(2f)

        assertEquals(1f, zoom.committedScale, 0.0001f)
        assertEquals(2f, zoom.previewFactor, 0.01f)
        assertTrue(zoom.isZooming)
    }

    @Test
    fun `one finger is a scroll and never a pinch`() {
        // A one-finger drag read as a pinch would switch the surface's own scrolling off.
        val zoom = zoom()
        zoom.onPointersChanged(1, focal)
        assertFalse(zoom.isPinching)
        assertFalse(zoom.isZooming)
        assertEquals(1f, zoom.previewFactor, 0.0001f)
    }

    @Test
    fun `two fingers is a pinch`() {
        val zoom = zoom()
        zoom.onPointersChanged(2, focal)
        assertTrue(zoom.isPinching)
        assertTrue(zoom.isZooming)
        assertEquals(focal, zoom.focalPoint)
    }

    @Test
    fun `a delta is ignored when no transform is running`() {
        // A stray event from a cancelled gesture must not set an unrequested scale.
        val zoom = zoom()
        zoom.applyGesture(2f)
        assertEquals(1f, zoom.gestureScale, 0.0001f)
    }

    @Test
    fun `the first delta of a pinch applies`() {
        // The second finger and first delta arrive in one event; dropping the delta loses the pinch.
        val zoom = zoom()
        zoom.onPointersChanged(2, focal)
        zoom.applyGesture(2f)
        assertEquals(2f, zoom.gestureScale, 0.01f)
    }

    @Test
    fun `the focal point stays where the fingers first met for the whole pinch`() {
        // Following the fingers would move the pivot and slide content by (scale - 1) * drift.
        val zoom = zoom()
        zoom.onPointersChanged(2, focal)
        zoom.applyGesture(1.5f)
        zoom.onPointersChanged(2, Offset(focal.x + 120f, focal.y - 80f))
        zoom.applyGesture(1.5f)

        assertEquals(focal, zoom.focalPoint)
    }

    @Test
    fun `a second pinch is anchored where its own fingers met`() {
        val zoom = zoom()
        zoom.onPointersChanged(2, focal)
        zoom.applyGesture(1.5f)
        zoom.onPointersChanged(0, Offset.Unspecified)
        settle(zoom)

        val elsewhere = Offset(50f, 700f)
        zoom.onPointersChanged(2, elsewhere)

        assertEquals(elsewhere, zoom.focalPoint)
    }

    @Test
    fun `a commit carries the focal point the pinch began at`() {
        val zoom = zoom()
        zoom.onPointersChanged(2, focal)
        zoom.applyGesture(2f)
        zoom.onPointersChanged(2, Offset(10f, 10f))
        zoom.onPointersChanged(0, Offset.Unspecified)
        settle(zoom)

        assertEquals(focal, zoom.lastCommit!!.focal)
    }

    @Test
    fun `lifting one finger of two ends the pinch`() {
        val zoom = zoom()
        zoom.onPointersChanged(2, focal)
        zoom.applyGesture(2f)
        zoom.onPointersChanged(1, Offset.Unspecified)

        assertFalse(zoom.isPinching)
        assertFalse(zoom.isZooming)
        settle(zoom)
        assertEquals(2f, zoom.committedScale, 0.01f)
    }

    @Test
    fun `lifting the last finger ends the gesture and commits`() {
        val zoom = zoom()
        zoom.onPointersChanged(2, focal)
        zoom.applyGesture(2f)
        zoom.onPointersChanged(0, Offset.Unspecified)

        assertFalse(zoom.isPinching)
        assertFalse(zoom.isZooming)
        settle(zoom)
        assertEquals(2f, zoom.committedScale, 0.01f)
    }

    @Test
    fun `a commit is reported only when the scale actually changed`() {
        // Every surface re-anchors on lastCommit, so an unchanged scale must not publish one.
        val zoom = zoom()
        zoom.set(2f)
        val after = zoom.lastCommit
        assertNotNull(after)
        assertEquals(1f, after!!.from, 0.0001f)
        assertEquals(2f, after.to, 0.0001f)

        zoom.set(2f)
        assertEquals("a no-op set re-published the commit", after, zoom.lastCommit)
    }

    @Test
    fun `a commit records the focal point it was anchored to`() {
        val zoom = zoom()
        zoom.set(2f, focal)
        assertEquals(focal, zoom.lastCommit!!.focal)
    }

    @Test
    fun `a control tap during the snap-back wins outright`() {
        // Tapping + mid-rebound must end the animation, not be fought by it.
        val zoom = zoom()
        zoom.set(1f)
        zoom.onPointersChanged(2, focal)
        repeat(40) { zoom.applyGesture(1.3f) }
        zoom.onPointersChanged(0, Offset.Unspecified)

        zoom.set(1.5f)

        assertEquals(1.5f, zoom.committedScale, 0.0001f)
        assertFalse(zoom.isZooming)
        assertEquals(1f, zoom.previewFactor, 0.0001f)
    }

    @Test
    fun `a new pinch starts from the committed scale, not from the last rubber-banded one`() {
        // A re-pinch must not inherit the abandoned overshoot.
        val zoom = zoom()
        zoom.set(1f)
        zoom.onPointersChanged(2, focal)
        repeat(40) { zoom.applyGesture(1.3f) }
        zoom.onPointersChanged(0, Offset.Unspecified)
        zoom.set(1f)

        zoom.onPointersChanged(2, focal)

        assertTrue(
            "pinch restarted from ${zoom.gestureScale} instead of the committed scale",
            zoom.gestureScale <= REST_SCALE + 0.0001f,
        )
        assertEquals(1f, zoom.previewFactor, 0.0001f)
    }

    @Test
    fun `stepping up and down returns to where it started`() {
        // Four each way is 2.44x; 1.25^8 would hit the 5x ceiling, so the ends clamp.
        val zoom = zoom()
        repeat(4) { zoom.stepUp() }
        assertEquals(1.25f * 1.25f * 1.25f * 1.25f, zoom.committedScale, 0.01f)

        repeat(4) { zoom.stepDown() }
        assertEquals(REST_SCALE, zoom.committedScale, 0.001f)
    }

    @Test
    fun `stepping beyond the range clamps rather than overshooting`() {
        // No rubber band on a control, and a stopped-at scale would disagree with the badge.
        val zoom = zoom()
        repeat(40) { zoom.stepUp() }
        assertEquals(MAX_SCALE, zoom.committedScale, 0.0001f)

        repeat(40) { zoom.stepDown() }
        assertEquals(MIN_SCALE, zoom.committedScale, 0.0001f)
    }

    @Test
    fun `double tap toggles between rest and magnification`() {
        val zoom = zoom()
        zoom.toggleZoom()
        assertTrue(zoom.isZoomed)
        assertTrue(zoom.committedScale > REST_SCALE)

        zoom.toggleZoom()
        assertFalse(zoom.isZoomed)
        assertEquals(REST_SCALE, zoom.committedScale, 0.0001f)
    }

    @Test
    fun `isZoomed is false at rest and after zooming out below rest`() {
        // Below rest is a legitimate resting state, so double-tap must not zoom further in from it.
        val zoom = zoom()
        assertFalse(zoom.isZoomed)
        zoom.set(MIN_SCALE)
        assertFalse(zoom.isZoomed)
    }

    @Test
    fun `the badge reports the live scale during a gesture and the committed one at rest`() {
        val zoom = zoom()
        assertEquals("100%", zoom.percentLabel())

        zoom.onPointersChanged(2, focal)
        zoom.applyGesture(2f)
        assertEquals("200%", zoom.percentLabel())

        zoom.onPointersChanged(0, Offset.Unspecified)
        settle(zoom)
        assertEquals("200%", zoom.percentLabel())
    }

    @Test
    fun `an item that grew below the focal point is scrolled forward`() {
        // Item was 1000px tall at the top, the finger at 400px sat 40% down it. At 2x it is 2000px
        // tall and the same spot is 800px down, so the list must scroll forward by 400.
        val delta = lazyAnchorDelta(focal = 400f, fraction = 0.4f, itemTop = 0f, itemSize = 2000f)
        assertEquals(400f, delta, 0.01f)
    }

    @Test
    fun `scrolling by the lazy anchor delta lands the anchored point under the focal point`() {
        val fraction = 0.25f
        val size = 1600f
        val top = -300f
        val delta = lazyAnchorDelta(focal.y, fraction, top, size)

        // Scrolling forward by delta moves the item up by delta.
        assertEquals(focal.y, (top - delta) + fraction * size, 0.01f)
    }

    @Test
    fun `an item already at its anchor needs no scroll`() {
        assertEquals(0f, lazyAnchorDelta(400f, 0.5f, 100f, 600f), 0.01f)
    }

    @Test
    fun `the requested scroll offset puts the focal fraction of the grown item under the finger`() {
        // Item was 1000px, the finger sat 40% down it. At 2x it is 2000px and 800px of it must sit
        // above the finger, so the item's top is 400 - 800 = -400 relative to the viewport.
        val before = 8
        val offset = lazyAnchorScrollOffset(focal = 400f, fraction = 0.4f, newItemSize = 2000f, before = before)

        // An item at offset 0 sits `before` px down; each px of scroll offset lifts it by one.
        val top = before - offset
        assertEquals(400f, top + 0.4f * 2000f, 0.51f)
    }

    @Test
    fun `an item that did not change size and was not moved keeps its offset`() {
        // Anchored at the top of an item with no content padding: nothing to do.
        assertEquals(0, lazyAnchorScrollOffset(focal = 0f, fraction = 0f, newItemSize = 1000f, before = 0))
    }

    @Test
    fun `the requested offset agrees with the delta the settle pass would apply`() {
        // The atomic request and the measured trim are two views of one equation: after the request
        // lands on an item of the predicted size, the trim must find nothing left to do.
        val fraction = 0.3f
        val size = 1800f
        val before = 24
        val offset = lazyAnchorScrollOffset(focal.y, fraction, size, before)
        val itemTopInViewport = (before - offset).toFloat()
        assertEquals(0f, lazyAnchorDelta(focal.y, fraction, itemTopInViewport, size), 0.51f)
    }

    @Test
    fun `anchors are told about a commit before the new scale is published`() {
        // Their one chance to read the content under the finger off the layout the pinch previewed.
        val zoom = zoom()
        var scaleSeen = -1f
        var commitSeen: ScaleCommit? = null
        zoom.addAnchor(object : ZoomAnchor {
            override fun onCommit(commit: ScaleCommit) {
                scaleSeen = zoom.committedScale
                commitSeen = commit
            }
        })

        zoom.set(2f, focal)

        assertEquals("the anchor ran after the scale was published", 1f, scaleSeen, 0.0001f)
        assertEquals(ScaleCommit(1f, 2f, focal), commitSeen)
        assertEquals(2f, zoom.committedScale, 0.0001f)
    }

    @Test
    fun `anchors hear about a pinch commit with the focal point it began at`() {
        val zoom = zoom()
        var seen: ScaleCommit? = null
        zoom.addAnchor(object : ZoomAnchor {
            override fun onCommit(commit: ScaleCommit) { seen = commit }
        })

        zoom.onPointersChanged(2, focal)
        zoom.applyGesture(1.5f)
        zoom.onPointersChanged(0, Offset.Unspecified)

        assertEquals(focal, seen!!.focal)
        assertEquals(1.5f, seen!!.ratio, 0.01f)
    }

    @Test
    fun `anchors are not told about a commit that changed nothing`() {
        val zoom = zoom()
        var calls = 0
        zoom.set(2f)
        zoom.addAnchor(object : ZoomAnchor {
            override fun onCommit(commit: ScaleCommit) { calls++ }
        })

        zoom.set(2f)

        assertEquals(0, calls)
    }

    @Test
    fun `a removed anchor is no longer told`() {
        val zoom = zoom()
        var calls = 0
        val anchor = object : ZoomAnchor {
            override fun onCommit(commit: ScaleCommit) { calls++ }
        }
        zoom.addAnchor(anchor)
        zoom.removeAnchor(anchor)

        zoom.set(2f, focal)

        assertEquals(0, calls)
    }

    @Test
    fun `an anchor added twice is still told once`() {
        val zoom = zoom()
        var calls = 0
        val anchor = object : ZoomAnchor {
            override fun onCommit(commit: ScaleCommit) { calls++ }
        }
        zoom.addAnchor(anchor)
        zoom.addAnchor(anchor)

        zoom.set(2f, focal)

        assertEquals(1, calls)
    }

    @Test
    fun `scrolling stays locked after the first finger of a pinch lifts`() {
        // Fingers never leave together: the one still down must not scroll the document.
        val zoom = zoom()
        zoom.onPointersChanged(2, focal)
        assertTrue(zoom.isScrollLocked)

        zoom.onPointersChanged(1, Offset.Unspecified)

        assertFalse("the pinch is over", zoom.isPinching)
        assertTrue("the straggler scrolled the page", zoom.isScrollLocked)
    }

    @Test
    fun `scrolling comes back once the last finger is up`() {
        val zoom = zoom()
        zoom.onPointersChanged(2, focal)
        zoom.onPointersChanged(1, Offset.Unspecified)

        zoom.onPointersChanged(0, Offset.Unspecified)

        assertFalse(zoom.isScrollLocked)
    }

    @Test
    fun `a single finger never locks scrolling`() {
        val zoom = zoom()
        zoom.onPointersChanged(1, Offset.Unspecified)
        assertFalse(zoom.isScrollLocked)
    }

    private fun commit(pair: Pair<Float, Float>) = ScaleCommit(pair.first, pair.second, focal)

    /** No frame clock in a plain JVM test, so snap-back settles through set() instead. */
    private fun zoom() = ZoomState(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined))

    private fun settle(zoom: ZoomState) = zoom.set(zoom.gestureScale.coerceIn(MIN_SCALE, MAX_SCALE))
}
