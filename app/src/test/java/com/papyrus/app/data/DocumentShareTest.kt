package com.papyrus.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** A wrong MIME here decides which share targets appear at all, so the chain is pinned. */
class DocumentShareTest {

    @Test
    fun `the provider's own type wins`() {
        assertEquals("application/pdf", shareMimeType(DocumentFormat.PDF, "application/pdf"))
    }

    @Test
    fun `a specific provider type is trusted even over the detected format`() {
        assertEquals(
            "application/vnd.oasis.opendocument.text",
            shareMimeType(DocumentFormat.UNKNOWN, "application/vnd.oasis.opendocument.text"),
        )
    }

    @Test
    fun `a placeholder type loses to the detected format`() {
        val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        assertEquals(docx, shareMimeType(DocumentFormat.DOCX, "application/octet-stream"))
        assertEquals(docx, shareMimeType(DocumentFormat.DOCX, "application/binary"))
        assertEquals(docx, shareMimeType(DocumentFormat.DOCX, "*/*"))
    }

    @Test
    fun `plain text is kept for the formats that are plain text`() {
        assertEquals("text/plain", shareMimeType(DocumentFormat.TEXT, "text/plain"))
        assertEquals("text/plain", shareMimeType(DocumentFormat.CODE, "text/plain"))
    }

    @Test
    fun `plain text loses to the declared type for a format that is not plain text`() {
        assertEquals("text/markdown", shareMimeType(DocumentFormat.MARKDOWN, "text/plain"))
    }

    @Test
    fun `a format with no declared type still shares`() {
        assertEquals("*/*", shareMimeType(DocumentFormat.CODE, null))
        assertEquals("*/*", shareMimeType(DocumentFormat.UNKNOWN, null))
    }

    @Test
    fun `a blank or padded type is treated as no type`() {
        assertEquals("text/markdown", shareMimeType(DocumentFormat.MARKDOWN, "   "))
        assertEquals("application/pdf", shareMimeType(DocumentFormat.PDF, " application/pdf "))
    }

    @Test
    fun `an unknown provider type is kept rather than replaced`() {
        assertEquals("application/x-custom", shareMimeType(DocumentFormat.PDF, "application/x-custom"))
    }
}