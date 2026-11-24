package io.github.pixelrick420.papyrus.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.InputStream
import kotlin.coroutines.cancellation.CancellationException

class ThumbnailLoaderTest {

    @Test
    fun rendererOutOfMemoryErrorDegradesToUnavailable() = runBlocking {
        val attempt = attemptThumbnailRender<String> { throw OutOfMemoryError("heap") }

        assertEquals(
            ThumbnailResult.Unavailable("OutOfMemoryError"),
            (attempt as ThumbnailAttempt.Failed).spec.result,
        )
    }

    @Test
    fun rendererStackOverflowErrorDegradesToUnavailable() = runBlocking {
        val attempt = attemptThumbnailRender<String> { throw StackOverflowError() }

        assertEquals(
            ThumbnailResult.Unavailable("StackOverflowError"),
            (attempt as ThumbnailAttempt.Failed).spec.result,
        )
    }

    @Test
    fun rendererCancellationStillPropagates() {
        assertThrows(CancellationException::class.java) {
            runBlocking { attemptThumbnailRender<String> { throw CancellationException() } }
        }
    }

    @Test
    fun textStreamSecurityExceptionMapsToNoAccess() = runBlocking {
        val attempt = attemptThumbnailRender {
            withThumbnailStream({ revokedStream() }) { input -> input.read() }
        }

        assertSame(ThumbnailResult.NoAccess, (attempt as ThumbnailAttempt.Failed).spec.result)
    }

    @Test
    fun officeStreamSecurityExceptionMapsToNoAccess() = runBlocking {
        val attempt = attemptThumbnailRender {
            requireThumbnailStream("content://office") { revokedStream() }
        }

        assertSame(ThumbnailResult.NoAccess, (attempt as ThumbnailAttempt.Failed).spec.result)
    }

    @Test
    fun epubStreamSecurityExceptionMapsToNoAccess() = runBlocking {
        val attempt = attemptThumbnailRender {
            requireThumbnailStream("content://epub") { revokedStream() }
        }

        assertSame(ThumbnailResult.NoAccess, (attempt as ThumbnailAttempt.Failed).spec.result)
    }

    @Test
    fun pptxStreamSecurityExceptionMapsToNoAccess() = runBlocking {
        val attempt = attemptThumbnailRender {
            withPptxThumbnailBudget { requireThumbnailStream("content://pptx") { revokedStream() } }
        }

        assertSame(ThumbnailResult.NoAccess, (attempt as ThumbnailAttempt.Failed).spec.result)
    }

    @Test
    fun missingStreamStaysUnavailableRatherThanNoAccess() = runBlocking {
        val attempt = attemptThumbnailRender {
            requireThumbnailStream("content://missing") { null }
        }

        assertEquals(
            ThumbnailResult.Unavailable("content://missing"),
            (attempt as ThumbnailAttempt.Failed).spec.result,
        )
    }

    @Test
    fun pptxOutOfMemoryStillYieldsNoBitmap() = runBlocking {
        val attempt = attemptThumbnailRender {
            withPptxThumbnailBudget<String> { throw OutOfMemoryError() }
        }

        assertEquals(ThumbnailAttempt.Rendered<String>(null), attempt)
    }

    @Test
    fun pptxGenericExceptionStillYieldsNoBitmap() = runBlocking {
        val attempt = attemptThumbnailRender {
            withPptxThumbnailBudget<String> { throw IllegalStateException("bad pptx") }
        }

        assertEquals(ThumbnailAttempt.Rendered<String>(null), attempt)
    }

    private fun revokedStream(): InputStream? = throw SecurityException("grant revoked")
}
