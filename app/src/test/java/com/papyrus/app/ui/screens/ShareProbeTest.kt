package com.papyrus.app.ui.screens

import com.papyrus.app.data.SafAccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * The share pre-flight runs concurrently, so what it must still get right is the serial contract:
 * one probe per file, a type only for readable ones, selection order kept, and a hard ceiling on
 * how many probes are in flight at once.
 */
class ShareProbeTest {

    @Test
    fun `each document is probed once, only readable ones get a type, order is kept`() = runBlocking {
        val verdicts = mapOf(
            1 to SafAccess.Readable,
            2 to SafAccess.Unreadable,
            3 to SafAccess.Readable,
            4 to SafAccess.Missing,
        )
        val probes = Collections.synchronizedList(mutableListOf<Int>())
        val types = Collections.synchronizedList(mutableListOf<Int>())
        val events = Collections.synchronizedList(mutableListOf<String>())

        val result = probeShareable(
            selected = listOf(1, 2, 3, 4),
            probe = { id ->
                probes += id
                events += "probe:$id"
                verdicts.getValue(id)
            },
            type = { id ->
                types += id
                events += "type:$id"
                "type-$id"
            },
        )

        assertEquals(listOf(1, 2, 3, 4), probes.sorted())
        assertEquals(listOf(1, 3), types.sorted())
        // Selection order survives the concurrency: same answer as the serial filter gave.
        assertEquals(listOf(1 to "type-1", 3 to "type-3"), result)
        // And within a document, the probe happens before its type is asked.
        for (readable in listOf(1, 3)) {
            assertTrue(
                "probe must precede type for document $readable",
                events.indexOf("probe:$readable") < events.indexOf("type:$readable"),
            )
        }
    }

    @Test
    fun `probes overlap but never past the limit`() = runBlocking {
        val limit = 2
        val inFlight = AtomicInteger()
        val widest = AtomicInteger()

        val result = probeShareable(
            selected = listOf(1, 2, 3, 4, 5, 6),
            limit = limit,
            probe = { id ->
                widest.updateAndGet { seen -> maxOf(seen, inFlight.incrementAndGet()) }
                delay(25)
                inFlight.decrementAndGet()
                SafAccess.Readable
            },
            type = { "text/plain" },
        )

        assertEquals(6, result.size)
        // Reaching the limit proves the probes really overlap; never passing it proves the bound holds.
        assertEquals(limit, widest.get())
    }

    @Test
    fun `no readable document means an empty result`() = runBlocking {
        val result = probeShareable(
            selected = listOf(1, 2),
            probe = { SafAccess.NoAccess },
            type = { error("an unreadable document's type is never asked") },
        )

        assertTrue(result.isEmpty())
    }
}
