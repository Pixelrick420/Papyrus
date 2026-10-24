package com.papyrus.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The save policy: no write before a restore, none without movement, debounced, flushed on demand. */
class PositionTrackerTest {

    private var clock = 0L
    private val tracker = PositionTracker(idleMillis = 1_500L, now = { clock })

    private fun at(unit: Int, fraction: Float = 0f, zoom: Float = 1f, atEnd: Boolean = false) =
        PositionSample(ReadingLocation(LocationKind.PAGE, unit, fraction), unitCount = 200, atEnd = atEnd, zoom = zoom)

    private fun advance(ms: Long) {
        clock += ms
    }

    @Test
    fun `nothing is saved before a pending restore has been applied`() {
        tracker.beginLoad(restorePending = true)
        // The list sits at the top while the target page is still being found: these must be dropped.
        tracker.observe(at(0))
        advance(10_000)
        tracker.observe(at(0, 0.3f))
        advance(10_000)
        assertNull(tracker.take(force = true))
        assertNull(tracker.nextDelay())
        assertNull(tracker.latest)
    }

    @Test
    fun `the stored position is not overwritten by the top that shows while restoring`() {
        tracker.beginLoad(restorePending = true)
        tracker.observe(at(0)) // the classic bug: a save of page 0 here would erase page 120
        tracker.restoreApplied(at(120, 0.4f))
        assertNull("restore alone is not movement", tracker.take(force = true))
        tracker.observe(at(120, 0.4f))
        assertNull(tracker.take(force = true))
        assertEquals(at(120, 0.4f), tracker.latest)
    }

    @Test
    fun `a restore that is never applied saves nothing, so a failed load cannot erase a position`() {
        tracker.beginLoad(restorePending = true)
        advance(60_000)
        assertNull(tracker.take(force = true))
    }

    @Test
    fun `opening and leaving without scrolling writes nothing`() {
        tracker.beginLoad(restorePending = false)
        tracker.observe(at(0))
        advance(5_000)
        assertNull(tracker.take(force = false))
        assertNull(tracker.take(force = true))
        assertNull(tracker.nextDelay())
    }

    @Test
    fun `rounding jitter after a restore is not movement`() {
        tracker.beginLoad(restorePending = true)
        tracker.restoreApplied(at(50, 0.500f))
        tracker.observe(at(50, 0.505f))
        assertNull(tracker.take(force = true))
    }

    @Test
    fun `the first observation after a fresh open is the starting point, not a save`() {
        tracker.beginLoad(restorePending = false)
        tracker.observe(at(0))
        assertNull(tracker.take(force = true))
        tracker.observe(at(3))
        assertEquals(at(3), tracker.take(force = true))
    }

    @Test
    fun `scrolling after a restore is saved`() {
        tracker.beginLoad(restorePending = true)
        tracker.restoreApplied(at(120, 0.4f))
        tracker.observe(at(125, 0.1f))
        advance(1_500)
        assertEquals(at(125, 0.1f), tracker.take(force = false))
    }

    @Test
    fun `a pinch alone is movement`() {
        tracker.beginLoad(restorePending = false)
        tracker.observe(at(2, zoom = 1f))
        tracker.observe(at(2, zoom = 2f))
        assertEquals(at(2, zoom = 2f), tracker.take(force = true))
    }

    @Test
    fun `returning to the starting point after moving is still saved`() {
        tracker.beginLoad(restorePending = false)
        tracker.observe(at(0))
        tracker.observe(at(8))
        assertEquals(at(8), tracker.take(force = true))
        tracker.observe(at(0)) // read ahead, then went back to the top: that is where they stopped
        assertEquals(at(0), tracker.take(force = true))
    }

    @Test
    fun `a save waits for the idle period and is handed over once`() {
        tracker.beginLoad(restorePending = false)
        tracker.observe(at(0))
        tracker.observe(at(4))
        assertEquals(1_500L, tracker.nextDelay())
        advance(1_000)
        assertNull(tracker.take(force = false))
        assertEquals(500L, tracker.nextDelay())
        advance(500)
        assertEquals(0L, tracker.nextDelay())
        assertEquals(at(4), tracker.take(force = false))
        assertNull("handed over once", tracker.take(force = false))
        assertNull(tracker.nextDelay())
    }

    @Test
    fun `continuous scrolling keeps pushing the save back`() {
        tracker.beginLoad(restorePending = false)
        tracker.observe(at(0))
        for (unit in 1..5) {
            advance(1_000)
            tracker.observe(at(unit))
            assertNull("still scrolling at $unit", tracker.take(force = false))
        }
        advance(1_500)
        assertEquals(at(5), tracker.take(force = false))
    }

    @Test
    fun `an unchanged observation does not restart the debounce`() {
        tracker.beginLoad(restorePending = false)
        tracker.observe(at(0))
        tracker.observe(at(4))
        advance(1_000)
        tracker.observe(at(4))
        advance(500)
        assertEquals(at(4), tracker.take(force = false))
    }

    @Test
    fun `flush on stop hands over the pending position immediately`() {
        tracker.beginLoad(restorePending = false)
        tracker.observe(at(0))
        tracker.observe(at(9))
        assertNull(tracker.take(force = false))
        assertEquals(at(9), tracker.take(force = true))
        assertNull(tracker.take(force = true))
    }

    @Test
    fun `reaching the end is movement even when the location is unchanged`() {
        tracker.beginLoad(restorePending = true)
        tracker.restoreApplied(at(199, 0.9f))
        tracker.observe(at(199, 0.9f, atEnd = true))
        assertEquals(at(199, 0.9f, atEnd = true), tracker.take(force = true))
    }

    @Test
    fun `a reload closes the gate again but keeps the last position for restoring`() {
        tracker.beginLoad(restorePending = false)
        tracker.observe(at(0))
        tracker.observe(at(30, 0.2f))
        assertEquals(at(30, 0.2f), tracker.latest)
        tracker.beginLoad(restorePending = true) // re-grant: the viewer is rebuilt at the top
        tracker.observe(at(0))
        assertEquals("the top shown during the reload is not remembered", at(30, 0.2f), tracker.latest)
        tracker.restoreApplied(at(30, 0.2f))
        assertEquals(at(30, 0.2f), tracker.latest)
    }

    @Test
    fun `an unsaved position survives a reload and is written once the gate reopens`() {
        tracker.beginLoad(restorePending = false)
        tracker.observe(at(0))
        tracker.observe(at(30))
        tracker.beginLoad(restorePending = true)
        assertNull("gate shut: nothing written while the content reloads", tracker.take(force = true))
        tracker.restoreApplied(at(30))
        assertEquals(at(30), tracker.take(force = true))
    }
}
