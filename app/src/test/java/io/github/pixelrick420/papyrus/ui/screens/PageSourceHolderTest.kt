package io.github.pixelrick420.papyrus.ui.screens

import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSourceHolderTest {

    private class Fake(val uri: String) : Closeable {
        val closes = AtomicInteger()

        override fun close() {
            closes.incrementAndGet()
        }
    }

    private val made = mutableListOf<Fake>()

    private fun holder() = PageSourceHolder { uri -> Fake(uri).also { synchronized(made) { made += it } } }

    @Test
    fun `the same uri gets the same source`() {
        val holder = holder()

        assertSame(holder.get("a"), holder.get("a"))
        assertEquals(1, made.size)
    }

    @Test
    fun `callers racing for a source build exactly one`() {
        val holder = holder()
        val callers = 16
        val start = CyclicBarrier(callers)
        val pool = Executors.newFixedThreadPool(callers)
        try {
            val got = (1..callers).map { pool.submit<Fake?> { start.await(); holder.get("a") } }.map { it.get(5, TimeUnit.SECONDS) }

            assertEquals(1, made.size)
            assertTrue(got.all { it === made[0] })
            assertEquals(0, made[0].closes.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `callers racing across a uri change leave one open source and every other closed once`() {
        val holder = holder()
        val callers = 16
        val start = CyclicBarrier(callers)
        val done = CountDownLatch(callers)
        val pool = Executors.newFixedThreadPool(callers)
        try {
            repeat(callers) { n ->
                pool.execute {
                    start.await()
                    repeat(50) { holder.get(if ((n + it) % 2 == 0) "a" else "b") }
                    done.countDown()
                }
            }
            assertTrue(done.await(10, TimeUnit.SECONDS))

            holder.release()

            assertTrue(made.all { it.closes.get() == 1 })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a new uri closes the old source and opens another`() {
        val holder = holder()
        val first = holder.get("a")

        val second = holder.get("b")

        assertNotSame(first, second)
        assertEquals(1, made[0].closes.get())
        assertEquals(0, made[1].closes.get())
    }

    @Test
    fun `reset closes the source and the next get opens afresh`() {
        val holder = holder()
        val first = holder.get("a")

        holder.reset()
        holder.reset()

        assertEquals(1, made[0].closes.get())
        assertNotSame(first, holder.get("a"))
    }

    @Test
    fun `after release nothing opens and the source is closed once`() {
        val holder = holder()
        holder.get("a")

        holder.release()
        holder.release()

        assertNull(holder.get("a"))
        assertEquals(1, made.size)
        assertEquals(1, made[0].closes.get())
    }
}
