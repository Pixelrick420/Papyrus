package io.github.pixelrick420.papyrus.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SafStorageTest {

    @Test
    fun safQueryReturnsDefaultWhenProviderThrows() {
        val failures = listOf(
            UnsupportedOperationException("unsupported"),
            IllegalStateException("closed"),
            NullPointerException("null cursor"),
            IllegalArgumentException("bad uri"),
        )

        failures.forEach { failure ->
            assertEquals("fallback", querySafely("fallback") { throw failure })
        }
    }

    @Test
    fun safQueryKeepsSuccessfulResult() {
        assertEquals("value", querySafely("fallback") { "value" })
    }

    @Test
    fun safQueryRethrowsCancellation() {
        assertThrows(CancellationException::class.java) {
            querySafely("fallback") { throw CancellationException("cancelled") }
        }
    }
}
