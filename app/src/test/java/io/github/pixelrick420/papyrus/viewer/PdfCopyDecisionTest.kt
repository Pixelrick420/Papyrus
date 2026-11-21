package io.github.pixelrick420.papyrus.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfCopyDecisionTest {

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
    fun `a descriptor with no reported size is copied`() {
        assertEquals(
            PdfOpenFallback.COPY,
            pdfOpenFallback(statSize = -1L, seekable = true, hasPdfHeader = true, usableSpace = 0L),
        )
    }
}
