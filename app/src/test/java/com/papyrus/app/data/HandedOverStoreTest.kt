package com.papyrus.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HandedOverStoreTest {

    @Test
    fun `ids count down from the first negative id the viewer leaves free`() {
        val store = HandedOverStore<String>()

        assertEquals(HandedOverStore.FIRST_ID, store.add { "a" })
        assertEquals(HandedOverStore.FIRST_ID - 1, store.add { "b" })
        assertEquals(-2L, HandedOverStore.FIRST_ID)
    }

    @Test
    fun `an entry is built with the id it is stored under`() {
        val store = HandedOverStore<String>()

        val id = store.add { "document $it" }

        assertEquals("document $id", store[id])
    }

    @Test
    fun `a second document leaves the first one in place`() {
        val store = HandedOverStore<String>()

        val first = store.add { "first" }
        val second = store.add { "second" }

        assertEquals("first", store[first])
        assertEquals("second", store[second])
    }

    @Test
    fun `past its capacity the entry used longest ago goes, not the whole set`() {
        val store = HandedOverStore<String>(capacity = 2)

        val first = store.add { "first" }
        val second = store.add { "second" }
        val third = store.add { "third" }

        assertNull(store[first])
        assertEquals("second", store[second])
        assertEquals("third", store[third])
    }

    @Test
    fun `reading an entry keeps it while an unread one ages out`() {
        val store = HandedOverStore<String>(capacity = 2)
        val first = store.add { "first" }
        val second = store.add { "second" }

        store[first]
        val third = store.add { "third" }

        assertEquals("first", store[first])
        assertNull(store[second])
        assertEquals("third", store[third])
    }

    @Test
    fun `an id that was never handed over is not found`() {
        assertNull(HandedOverStore<String>()[-99L])
    }

    @Test
    fun `setting replaces an entry and stores one that aged out again`() {
        val store = HandedOverStore<String>(capacity = 1)
        val first = store.add { "first" }

        store[first] = "re-granted"
        assertEquals("re-granted", store[first])

        val second = store.add { "second" }
        assertNull(store[first])

        store[first] = "back"
        assertEquals("back", store[first])
        assertNull(store[second])
    }

    @Test
    fun `adds from many threads get distinct ids and stay within capacity`() {
        val capacity = 16
        val store = HandedOverStore<Long>(capacity)
        val threads = 8
        val perThread = 250
        val ids = ConcurrentHashMap.newKeySet<Long>()
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val done = (1..threads).map {
                pool.submit {
                    ready.countDown()
                    go.await()
                    repeat(perThread) { ids += store.add { id -> id } }
                }
            }
            ready.await()
            go.countDown()
            done.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }

        assertEquals(threads * perThread, ids.size)
        val kept = ids.filter { store[it] != null }
        assertEquals(capacity, kept.size)
        kept.forEach { assertEquals(it, store[it]) }
    }
}
