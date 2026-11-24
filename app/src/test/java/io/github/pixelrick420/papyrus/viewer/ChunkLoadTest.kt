package io.github.pixelrick420.papyrus.viewer

import java.io.ByteArrayInputStream
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ChunkLoadTest {

    private val utf8 = TextEncoding(Charsets.UTF_8, 0, EncodingSource.UTF8)

    private class FakeView(private val outcome: () -> String) : TextChunkView {
        override val size: Int = 1
        override fun peek(index: Int): String? = null
        override suspend fun load(index: Int): String = outcome()
    }

    @Test
    fun `a chunk that loads comes back as its text`() = runBlocking {
        assertEquals("text", FakeView { "text" }.loadOrNull(0))
    }

    @Test
    fun `an unreadable chunk is null and the reason is reported`() = runBlocking {
        val failures = listOf(IOException("gone"), SecurityException("grant revoked"), IllegalStateException("provider died"))
        for (failure in failures) {
            val seen = mutableListOf<Exception>()

            assertNull(failure.javaClass.simpleName, FakeView { throw failure }.loadOrNull(0, seen::add))

            assertEquals(listOf<Exception>(failure), seen)
        }
    }

    @Test
    fun `cancellation is rethrown, not turned into a failed chunk`() {
        val view = FakeView { throw CancellationException("stop") }
        var reported = false

        assertThrows(CancellationException::class.java) { runBlocking { view.loadOrNull(0) { reported = true } } }

        assertFalse(reported)
    }

    @Test
    fun `a chunk whose provider fails is not cached empty and loads once the provider recovers`() = runBlocking {
        val bytes = (1..20).joinToString("\n") { "line $it" }.toByteArray()
        for (down in listOf(IOException("gone"), SecurityException("grant revoked"))) {
            var failure: Exception? = null
            val src = ByteSource { offset ->
                failure?.let { throw it }
                ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
            }
            val layout = ChunkLayout(linesPerChunk = 2, checkpointBytes = 1, checkpointChunks = 1)
            val view = indexChunks(src, src.open(0), utf8, layout).view

            failure = down
            assertNull(down.javaClass.simpleName, view.loadOrNull(3))
            assertNull("a failure leaves nothing behind", view.peek(3))

            failure = null
            assertEquals("line 7\nline 8", view.loadOrNull(3))
            assertEquals("line 7\nline 8", view.peek(3))
        }
    }
}
