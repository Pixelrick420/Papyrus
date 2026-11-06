package com.papyrus.app.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the background indexer may work: only if it is on, nobody is reading, and the battery is not being saved. */
class IndexingGateTest {

    private val enabled = MutableStateFlow(true)
    private val reading = MutableStateFlow(false)
    private val batterySaver = MutableStateFlow(false)
    private val gate = IndexingGate(enabled, reading, batterySaver)

    private fun isOpen(): Boolean = runBlocking { withTimeout(2_000) { gate.open.first() } }

    @Test
    fun `it is open when the setting is on, nobody is reading and the battery is not being saved`() {
        assertTrue(isOpen())
    }

    @Test
    fun `it is closed while a document is being read`() {
        reading.value = true

        assertFalse(isOpen())
    }

    @Test
    fun `it is closed under battery saver`() {
        batterySaver.value = true

        assertFalse(isOpen())
    }

    @Test
    fun `it is closed when the setting is off, whatever else is true`() {
        enabled.value = false

        assertFalse(isOpen())
    }

    @Test
    fun `any one reason is enough to keep it closed`() {
        reading.value = true
        batterySaver.value = true
        assertFalse(isOpen())

        reading.value = false
        assertFalse("battery saver alone should still hold it closed", isOpen())

        batterySaver.value = false
        assertTrue(isOpen())
    }

    @Test
    fun `a change that does not change the answer is not announced`() = runBlocking {
        // A collector that restarts on every emission would restart the indexer for nothing.
        val seen = mutableListOf<Boolean>()
        val collector = launch { gate.open.toCollection(seen) }
        delay(50)

        reading.value = true
        delay(50)
        // Already closed: a second reason changes nothing a collector can see.
        batterySaver.value = true
        delay(50)
        // And one reason gone leaves it closed.
        reading.value = false
        delay(50)
        collector.cancel()

        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun `the viewer counts as reading from the moment it enters until the last one leaves`() = runBlocking {
        val presence = ReaderPresence()
        suspend fun reading() = withTimeout(2_000) { presence.reading.first() }

        assertFalse(reading())
        presence.entered()
        assertTrue(reading())
        // A second viewer is entered before the first has left, as in a navigation transition.
        presence.entered()
        presence.left()
        assertTrue("one viewer is still open", reading())
        presence.left()
        assertFalse(reading())
    }

    @Test
    fun `an extra leave cannot leave the indexer paused for good`() = runBlocking {
        val presence = ReaderPresence()

        presence.left()
        presence.left()
        presence.entered()

        assertTrue(withTimeout(2_000) { presence.reading.first() })
        presence.left()
        assertFalse(withTimeout(2_000) { presence.reading.first() })
    }
}
