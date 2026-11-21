package io.github.pixelrick420.papyrus.ui.screens

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WriteFailureReporterTest {

    @Test
    fun `a failed write is reported and shown once`() = runBlocking {
        val reports = mutableListOf<Throwable>()
        val notices = mutableListOf<Int>()
        val reporter = WriteFailureReporter(reports::add, notices::add)

        reporter.runReporting(42) { error("disk full") }
        reporter.runReporting(42) { error("disk full again") }

        assertEquals(2, reports.size)
        assertEquals(listOf(42), notices)
    }

    @Test
    fun `cancellation propagates without a notice`() = runBlocking {
        val notices = mutableListOf<Int>()
        val reporter = WriteFailureReporter({}, notices::add)

        try {
            reporter.runReporting(42) { throw CancellationException("leaving") }
            fail("cancellation should propagate")
        } catch (e: CancellationException) {
            assertEquals("leaving", e.message)
        }
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `a successful write stays quiet`() = runBlocking {
        val reports = mutableListOf<Throwable>()
        val notices = mutableListOf<Int>()
        val reporter = WriteFailureReporter(reports::add, notices::add)

        reporter.runReporting(42) { }

        assertTrue(reports.isEmpty())
        assertTrue(notices.isEmpty())
    }
}
