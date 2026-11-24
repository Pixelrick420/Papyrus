package io.github.pixelrick420.papyrus.viewer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class PdfOpenFallbackTest {

    @Test
    fun `the header must be at the start of the sniffed bytes`() {
        assertTrue(startsWithPdfHeader("%PDF-1.7".toByteArray()))
        assertFalse(startsWithPdfHeader("junk%PDF-1.7".toByteArray()))
        assertFalse(startsWithPdfHeader("%PD".toByteArray()))
    }

    @Test
    fun `a corrupt regular file is not copied`() {
        assertEquals(
            PdfOpenFallback.FAIL_FORMAT,
            pdfOpenFallback(statSize = 500L, seekable = true, hasPdfHeader = true, usableSpace = 1_000L),
        )
    }

    @Test
    fun `a file without a PDF header is not copied`() {
        assertEquals(
            PdfOpenFallback.FAIL_FORMAT,
            pdfOpenFallback(statSize = -1L, seekable = false, hasPdfHeader = false, usableSpace = 1_000L),
        )
    }

    @Test
    fun `a known size that does not fit is not copied`() {
        assertEquals(
            PdfOpenFallback.FAIL_NO_SPACE,
            pdfOpenFallback(statSize = 1_001L, seekable = false, hasPdfHeader = true, usableSpace = 1_000L),
        )
    }

    @Test
    fun `a non-seekable descriptor is copied`() {
        assertEquals(
            PdfOpenFallback.COPY,
            pdfOpenFallback(statSize = -1L, seekable = false, hasPdfHeader = true, usableSpace = 1_000L),
        )
    }

    @Test
    fun `a descriptor with no reported size and no space is not copied`() {
        assertEquals(
            PdfOpenFallback.FAIL_NO_SPACE,
            pdfOpenFallback(statSize = -1L, seekable = true, hasPdfHeader = true, usableSpace = 0L),
        )
    }

    @Test
    fun `a known size above the copy cap is refused even with space`() {
        assertEquals(
            PdfOpenFallback.FAIL_NO_SPACE,
            pdfOpenFallback(
                statSize = MAX_PDF_COPY_BYTES + 1,
                seekable = false,
                hasPdfHeader = true,
                usableSpace = Long.MAX_VALUE,
            ),
        )
    }

    @Test
    fun `a bounded copy writes exactly the limit and refuses one byte more`() {
        val bytes = ByteArray(64 * 1024 + 1) { it.toByte() }
        val exact = ByteArrayOutputStream()
        copyBounded(ByteArrayInputStream(bytes), exact, bytes.size.toLong())
        assertArrayEquals(bytes, exact.toByteArray())

        assertThrows(IOException::class.java) {
            copyBounded(ByteArrayInputStream(bytes), ByteArrayOutputStream(), bytes.size - 1L)
        }
    }

    @Test
    fun nonSeekableRendererFailureRoutesToCopyFallback() {
        assertTrue(shouldCopyAfterOpenFailure(IllegalArgumentException("file descriptor not seekable")))
        assertTrue(shouldCopyAfterOpenFailure(IOException("read failed")))
        assertFalse(shouldCopyAfterOpenFailure(PdfPasswordException()))
        // A lost SAF grant is NoAccess, not a reason to copy the file.
        assertFalse(shouldCopyAfterOpenFailure(SecurityException("grant lost")))
    }

    @Test
    fun capitalisedQueryKeepsPlatformSearchNeedle() {
        val caps = PdfCapabilities(
            kind = PdfEngineKind.V35,
            text = true,
            search = true,
            selection = true,
            forms = true,
            annotations = false,
        )

        assertEquals("Report", platformSearchNeedle(caps, "Report"))
        assertEquals("report", platformSearchNeedle(caps, "report"))
        assertNull(platformSearchNeedle(PdfCapabilities.legacy(), "Report"))
    }

    @Test
    fun aMixedCaseQueryWithNoPlatformHitsIsRetriedByPdfBox() {
        assertTrue(shouldRetryMixedCaseSearch("Report", 0))
        assertFalse(shouldRetryMixedCaseSearch("Report", 1))
        assertFalse(shouldRetryMixedCaseSearch("report", 0))
    }
}
