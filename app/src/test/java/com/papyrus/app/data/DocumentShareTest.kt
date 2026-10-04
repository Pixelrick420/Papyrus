package com.papyrus.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** A wrong MIME here decides which share targets appear at all, so the chain is pinned. */
class DocumentShareTest {

    private fun doc(mimeType: String?, format: DocumentFormat) = DocumentEntity(
        uri = "content://com.android.providers.downloads.documents/document/1",
        title = "report",
        mimeType = mimeType,
        format = format,
        sizeBytes = 1,
        createdAt = 0,
        lastOpenedAt = 0,
    )

    @Test
    fun `the provider's own type wins`() {
        assertEquals("application/pdf", shareMimeType(doc("application/pdf", DocumentFormat.PDF)))
    }

    @Test
    fun `a specific provider type is trusted even over the detected format`() {
        assertEquals(
            "application/vnd.oasis.opendocument.text",
            shareMimeType(doc("application/vnd.oasis.opendocument.text", DocumentFormat.UNKNOWN)),
        )
    }

    @Test
    fun `a placeholder type loses to the detected format`() {
        val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        assertEquals(docx, shareMimeType(doc("application/octet-stream", DocumentFormat.DOCX)))
        assertEquals(docx, shareMimeType(doc("application/binary", DocumentFormat.DOCX)))
        assertEquals(docx, shareMimeType(doc("*/*", DocumentFormat.DOCX)))
    }

    @Test
    fun `plain text is kept for the formats that are plain text`() {
        assertEquals("text/plain", shareMimeType(doc("text/plain", DocumentFormat.TEXT)))
        assertEquals("text/plain", shareMimeType(doc("text/plain", DocumentFormat.CODE)))
    }

    @Test
    fun `plain text loses to the declared type for a format that is not plain text`() {
        assertEquals("text/markdown", shareMimeType(doc("text/plain", DocumentFormat.MARKDOWN)))
    }

    @Test
    fun `a format with no declared type still shares`() {
        assertEquals("*/*", shareMimeType(doc(null, DocumentFormat.CODE)))
        assertEquals("*/*", shareMimeType(doc(null, DocumentFormat.UNKNOWN)))
    }

    @Test
    fun `a blank or padded type is treated as no type`() {
        assertEquals("text/markdown", shareMimeType(doc("   ", DocumentFormat.MARKDOWN)))
        assertEquals("application/pdf", shareMimeType(doc(" application/pdf ", DocumentFormat.PDF)))
    }

    @Test
    fun `an unknown provider type is kept rather than replaced`() {
        assertEquals("application/x-custom", shareMimeType(doc("application/x-custom", DocumentFormat.PDF)))
    }
}