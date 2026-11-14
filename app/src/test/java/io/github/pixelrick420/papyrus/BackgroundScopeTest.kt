package io.github.pixelrick420.papyrus

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class BackgroundScopeTest {

    private val reported = LinkedBlockingQueue<Throwable>()
    private val uncaught = LinkedBlockingQueue<Throwable>()
    private val scope = backgroundScope { reported.add(it) }
    private var previous: Thread.UncaughtExceptionHandler? = null

    @Before
    fun watchTheProcessWideHandler() {
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, failure -> uncaught.add(failure) }
    }

    @After
    fun restoreTheHandler() {
        scope.cancel()
        Thread.setDefaultUncaughtExceptionHandler(previous)
    }

    @Test
    fun `a failure is reported as thrown and never reaches the uncaught handler`() {
        val diskFull = IOException("No space left on device")

        scope.launch { throw diskFull }

        assertSame(diskFull, reported.poll(5, TimeUnit.SECONDS))
        assertNull("the process would have ended", uncaught.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `an error is reported like an exception`() {
        val outOfMemory = OutOfMemoryError("indexing a very large file")

        scope.launch { throw outOfMemory }

        assertSame(outOfMemory, reported.poll(5, TimeUnit.SECONDS))
        assertNull(uncaught.poll(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a failing job does not stop the scope from running the next one`() = runBlocking {
        scope.launch { throw IOException("first") }
        reported.poll(5, TimeUnit.SECONDS)

        val ran = CompletableDeferred<Boolean>()
        scope.launch { ran.complete(true) }

        assertTrue(withTimeout(5_000) { ran.await() })
    }

    @Test
    fun `a failing job leaves the one beside it running`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Boolean>()
        scope.launch {
            release.await()
            finished.complete(true)
        }

        scope.launch { throw IOException("beside it") }
        reported.poll(5, TimeUnit.SECONDS)
        release.complete(Unit)

        assertTrue(withTimeout(5_000) { finished.await() })
        assertEquals(0, uncaught.size)
    }

    @Test
    fun `a job that is cancelled is not reported`() {
        val job = scope.launch { awaitCancellation() }

        job.cancel()

        assertNull(reported.poll(300, TimeUnit.MILLISECONDS))
    }
}
