package com.papyrus.app.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The undo window's contract: never committed twice, never committed after an Undo. */
class PendingRemovalTest {

    /** A clock the test turns by hand: tasks fire only when [advance] passes their time. */
    private class FakeTimer : PendingRemoval.Timer {
        private class Task(val at: Long, val action: () -> Unit) {
            var cancelled = false
        }

        private var now = 0L
        private val tasks = ArrayList<Task>()

        val live: Int get() = tasks.count { !it.cancelled }

        override fun schedule(delayMillis: Long, action: () -> Unit): PendingRemoval.Cancellation {
            val task = Task(now + delayMillis, action)
            tasks += task
            return PendingRemoval.Cancellation { task.cancelled = true }
        }

        fun advance(millis: Long) {
            now += millis
            for (task in tasks.toList()) {
                if (!task.cancelled && task.at <= now) {
                    task.cancelled = true
                    task.action()
                }
            }
        }

        /** Fires the [index]th timer even if cancelled, simulating a cancel that lost the race. */
        fun fireIgnoringCancellation(index: Int) {
            tasks[index].action()
        }
    }

    private val timer = FakeTimer()
    private val committed = ArrayList<String>()
    private val removal = PendingRemoval<String>(timer, windowMillis = 5_000) { committed += it }

    @Test
    fun `nothing is pending or committed to begin with`() {
        assertNull(removal.pending.value)
        removal.flush()
        assertEquals(emptyList<String>(), committed)
    }

    @Test
    fun `a removal is hidden at once and not committed inside the window`() {
        removal.begin("a")

        assertEquals("a", removal.pending.value?.item)
        timer.advance(4_999)
        assertEquals(emptyList<String>(), committed)
        assertEquals("a", removal.pending.value?.item)
    }

    @Test
    fun `the window ending commits once and clears the pending entry`() {
        removal.begin("a")
        timer.advance(5_000)

        assertEquals(listOf("a"), committed)
        assertNull(removal.pending.value)
        timer.advance(60_000)
        assertEquals(listOf("a"), committed)
    }

    @Test
    fun `undo brings the item back and nothing is ever committed`() {
        removal.begin("a")
        val token = removal.pending.value!!.token

        assertTrue(removal.undo(token))
        assertNull(removal.pending.value)
        timer.advance(60_000)
        removal.flush()

        assertEquals(emptyList<String>(), committed)
        assertEquals("the timer is cancelled, not merely ignored", 0, timer.live)
    }

    @Test
    fun `a second removal commits the first at once and gets a window of its own`() {
        removal.begin("a")
        timer.advance(2_000)
        removal.begin("b")

        assertEquals(listOf("a"), committed)
        assertEquals("b", removal.pending.value?.item)
        timer.advance(4_999) // 6,999 after "a" began, but only 4,999 after "b" did
        assertEquals(listOf("a"), committed)
        timer.advance(1)
        assertEquals(listOf("a", "b"), committed)
    }

    @Test
    fun `an undo aimed at a replaced entry does nothing`() {
        removal.begin("a")
        val stale = removal.pending.value!!.token
        removal.begin("b")

        assertFalse(removal.undo(stale))
        assertEquals("b", removal.pending.value?.item)
        timer.advance(5_000)
        assertEquals(listOf("a", "b"), committed)
    }

    @Test
    fun `an undo after the commit does nothing`() {
        removal.begin("a")
        val token = removal.pending.value!!.token
        timer.advance(5_000)

        assertFalse(removal.undo(token))
        assertEquals(listOf("a"), committed)
    }

    @Test
    fun `undoing twice is harmless`() {
        removal.begin("a")
        val token = removal.pending.value!!.token

        assertTrue(removal.undo(token))
        assertFalse(removal.undo(token))
    }

    @Test
    fun `flush commits the pending removal immediately and only once`() {
        removal.begin("a")
        removal.flush()
        removal.flush()
        timer.advance(60_000)

        assertEquals(listOf("a"), committed)
        assertNull(removal.pending.value)
    }

    @Test
    fun `a timeout that fires after the entry was taken cannot commit it again`() {
        removal.begin("a")
        removal.flush()
        // The timer's own cancel lost a race with its firing: the late callback must find nothing to commit.
        timer.fireIgnoringCancellation(0)

        assertEquals(listOf("a"), committed)
    }

    @Test
    fun `a stale timeout cannot commit the entry that replaced its own`() {
        removal.begin("a")
        removal.begin("b")
        // "a"'s timer fires late, after "b" is pending: it must not take "b".
        timer.fireIgnoringCancellation(0)

        assertEquals(listOf("a"), committed)
        assertEquals("b", removal.pending.value?.item)
    }

    @Test
    fun `every begin is committed exactly once whichever way its window ends`() {
        removal.begin("timeout")
        timer.advance(5_000)
        removal.begin("replaced")
        removal.begin("flushed")
        removal.flush()
        removal.begin("undone")
        removal.undo(removal.pending.value!!.token)

        assertEquals(listOf("timeout", "replaced", "flushed"), committed)
    }

    @Test
    fun `the entry is still pending while its commit starts`() {
        val seenPending = ArrayList<String?>()
        lateinit var observing: PendingRemoval<String>
        observing = PendingRemoval(timer, 5_000) { seenPending += observing.pending.value?.item }

        observing.begin("a")
        timer.advance(5_000)
        observing.begin("b")
        observing.begin("c")

        assertEquals(listOf<String?>("a", "b"), seenPending)
        assertEquals("c", observing.pending.value?.item)
    }

    @Test
    fun `a commit that throws still clears the entry so it cannot be committed again`() {
        var calls = 0
        val failing = PendingRemoval<String>(timer, 5_000) {
            calls++
            error("disk full")
        }
        failing.begin("a")

        runCatching { timer.advance(5_000) }
        failing.flush()

        assertEquals(1, calls)
        assertNull(failing.pending.value)
    }

    @Test
    fun `a batch is one entry`() {
        val batches = ArrayList<List<Int>>()
        val batchRemoval = PendingRemoval<List<Int>>(timer, 5_000) { batches += it }
        batchRemoval.begin(listOf(1, 2, 3))
        timer.advance(5_000)

        assertEquals(listOf(listOf(1, 2, 3)), batches)
    }
}
