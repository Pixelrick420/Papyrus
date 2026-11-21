package io.github.pixelrick420.papyrus.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pixelrick420.papyrus.data.DocumentEntity
import io.github.pixelrick420.papyrus.data.DocumentFormat
import io.github.pixelrick420.papyrus.data.ThumbnailLoader
import io.github.pixelrick420.papyrus.data.ThumbnailResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThumbnailFailureTest : E2eTest() {

    private val loader = ThumbnailLoader(appContext())

    @Test
    fun aCorruptPdfIsOnlyRenderedOnce() = runBlocking {
        val bytes = "this is not a pdf".toByteArray()
        val document = document(Fixtures.bytes("corrupt.pdf", bytes), DocumentFormat.PDF, bytes.size.toLong())

        assertTrue(loader.load(document) is ThumbnailResult.Unavailable)
        assertTrue(loader.load(document) is ThumbnailResult.Unavailable)

        assertEquals(1, loader.renderCount)
    }

    @Test
    fun anEncryptedOfficeFileIsOnlyRenderedOnce() = runBlocking {
        val bytes = Documents.encryptedOdt()
        val document = document(Fixtures.bytes("locked.odt", bytes), DocumentFormat.ODT, bytes.size.toLong())

        assertTrue(loader.load(document) is ThumbnailResult.Unavailable)
        assertTrue(loader.load(document) is ThumbnailResult.Unavailable)

        assertEquals(1, loader.renderCount)
    }

    private fun document(fixture: Fixture, format: DocumentFormat, size: Long) = DocumentEntity(
        uri = fixture.uri.toString(),
        title = fixture.name,
        format = format,
        sizeBytes = size,
        createdAt = 0,
        lastOpenedAt = 0,
    )

    private fun appContext() = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
}
