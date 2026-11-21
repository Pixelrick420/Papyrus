package io.github.pixelrick420.papyrus.viewer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.Executors

class PdfCopyWriterTest {

    @Test
    fun `a successful copy writes and does not delete`() = runBlocking {
        val deleted = mutableListOf<String>()
        var written: Map<Int, List<List<NormRect>>> = emptyMap()
        val writer = PdfCopyWriter<String, FakeDestination>(
            open = { FakeDestination() },
            writeCopy = { _, highlights -> written = highlights; true },
            delete = { deleted += it },
        )

        val marks = mapOf<Int, List<List<NormRect>>>(1 to emptyList())
        assertTrue(writer.write("content://copy", marks))

        assertEquals(marks, written)
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun `a failed write deletes the partial copy`() = runBlocking {
        val deleted = mutableListOf<String>()
        val writer = PdfCopyWriter<String, FakeDestination>(
            open = { FakeDestination() },
            writeCopy = { _, _ -> false },
            delete = { deleted += it },
        )

        assertFalse(writer.write("content://copy"))

        assertEquals(listOf("content://copy"), deleted)
    }

    @Test
    fun `cancellation mid-write still deletes the partial copy`() = runBlocking {
        val deleted = mutableListOf<String>()
        val writer = PdfCopyWriter<String, FakeDestination>(
            open = { FakeDestination() },
            writeCopy = { _, _ -> delay(10); throw CancellationException("cancelled") },
            delete = { deleted += it },
        )

        try {
            writer.write("content://copy")
            fail("cancellation should propagate")
        } catch (_: CancellationException) {
        }

        assertEquals(listOf("content://copy"), deleted)
    }

    @Test
    fun `a slow provider open does not run on the caller dispatcher`() = runBlocking {
        val caller = Executors.newSingleThreadExecutor { Thread(it, "caller") }
        val provider = Executors.newSingleThreadExecutor { Thread(it, "provider") }
        var openedOn = ""
        val writer = PdfCopyWriter<String, FakeDestination>(
            open = {
                openedOn = Thread.currentThread().name
                Thread.sleep(20)
                FakeDestination()
            },
            writeCopy = { _, _ -> true },
            delete = {},
            dispatcher = provider.asCoroutineDispatcher(),
        )
        try {
            withContext(caller.asCoroutineDispatcher()) { writer.write("content://copy") }
        } finally {
            caller.shutdownNow()
            provider.shutdownNow()
        }

        assertTrue("open ran on $openedOn", openedOn.startsWith("provider"))
    }

    private class FakeDestination : Closeable {
        override fun close() = Unit
    }
}
