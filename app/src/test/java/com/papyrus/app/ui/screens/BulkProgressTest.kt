package com.papyrus.app.ui.screens

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a multi-file Remove or Share shows how far it has got, and when it stays quiet. */
class BulkProgressTest {

    private val published = mutableListOf<BulkProgress?>()
    private val publish: (BulkProgress?) -> Unit = { synchronized(published) { published += it } }

    @Test
    fun `an action that finishes quickly never shows progress`() = runBlocking {
        trackBulk(BulkAction.REMOVE, total = 3, publish = publish, showAfterMillis = 200) { tracker ->
            repeat(3) { tracker.advance() }
        }

        // Only the clearing at the end: nothing was ever shown, so nothing flashed.
        assertEquals(listOf<BulkProgress?>(null), published)
    }

    @Test
    fun `a slow action shows the count so far, then each step, then clears`() = runBlocking {
        trackBulk(BulkAction.REMOVE, total = 3, publish = publish, showAfterMillis = 50) { tracker ->
            tracker.advance()
            delay(150)
            tracker.advance()
            tracker.advance()
        }

        assertEquals(
            listOf(
                // Revealed after the delay with the one step already done...
                BulkProgress(BulkAction.REMOVE, 1, 3),
                // ...then each step after it.
                BulkProgress(BulkAction.REMOVE, 2, 3),
                BulkProgress(BulkAction.REMOVE, 3, 3),
                null,
            ),
            published,
        )
    }

    @Test
    fun `one slow file is enough, even before any step has finished`() = runBlocking {
        // A share probes in parallel; the first answer can be seconds away, and the bar should not wait for it.
        trackBulk(BulkAction.SHARE, total = 2, publish = publish, showAfterMillis = 50) { tracker ->
            delay(150)
            tracker.advance()
            tracker.advance()
        }

        assertEquals(BulkProgress(BulkAction.SHARE, 0, 2), published.first())
        assertNull(published.last())
    }

    @Test
    fun `the result of the action is returned`() = runBlocking {
        val result = trackBulk(BulkAction.SHARE, total = 1, publish = publish) { 42 }

        assertEquals(42, result)
    }

    @Test
    fun `progress is taken away when the action fails`() = runBlocking {
        val failure = runCatching {
            trackBulk(BulkAction.REMOVE, total = 2, publish = publish, showAfterMillis = 20) {
                delay(100)
                error("boom")
            }
        }.exceptionOrNull()

        assertEquals("boom", failure?.message)
        assertNull("a failed action must not leave a bar behind", published.last())
    }

    @Test
    fun `progress is taken away when the action is cancelled`() = runBlocking {
        val job = launch {
            trackBulk(BulkAction.REMOVE, total = 2, publish = publish, showAfterMillis = 20) {
                delay(10_000)
            }
        }
        delay(100)
        assertTrue("the bar should be showing by now", published.any { it != null })

        job.cancel(CancellationException("screen gone"))
        job.join()

        assertNull(published.last())
    }

    @Test
    fun `steps counted from several coroutines at once are all counted, and published in order`() = runBlocking {
        val total = 200
        trackBulk(BulkAction.SHARE, total = total, publish = publish, showAfterMillis = 10) { tracker ->
            // Long enough that the bar is already showing when the steps start to land.
            delay(60)
            (1..total).map { async(Dispatchers.Default) { tracker.advance() } }.awaitAll()
        }

        val counts = published.filterNotNull().map { it.done }
        assertEquals(total, counts.last())
        // A slower thread must never publish a lower count after a higher one.
        assertEquals(counts.sorted(), counts)
    }

    @Test
    fun `the fraction runs from nothing to done and survives a zero total`() {
        assertEquals(0f, BulkProgress(BulkAction.REMOVE, 0, 4).fraction, 0f)
        assertEquals(0.5f, BulkProgress(BulkAction.REMOVE, 2, 4).fraction, 0f)
        assertEquals(1f, BulkProgress(BulkAction.REMOVE, 4, 4).fraction, 0f)
        assertEquals(0f, BulkProgress(BulkAction.REMOVE, 0, 0).fraction, 0f)
        // A count that overshoots the total, which a late step could do, still draws a full bar.
        assertEquals(1f, BulkProgress(BulkAction.REMOVE, 5, 4).fraction, 0f)
    }
}
