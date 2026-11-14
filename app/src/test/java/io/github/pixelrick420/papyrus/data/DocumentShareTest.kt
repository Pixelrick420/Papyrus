package io.github.pixelrick420.papyrus.data

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
        assertEquals("application/msword", shareMimeType(DocumentFormat.DOC, "application/octet-stream"))
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

    @Test
    fun `a multi-share of one type declares that type`() {
        assertEquals("application/pdf", sharedMimeType(listOf("application/pdf", "application/pdf")))
    }

    @Test
    fun `a multi-share of a single file declares its type`() {
        assertEquals("text/plain", sharedMimeType(listOf("text/plain")))
    }

    @Test
    fun `a multi-share of mixed types falls back to the wildcard`() {
        assertEquals("*/*", sharedMimeType(listOf("application/pdf", "application/msword")))
    }

    @Test
    fun `an empty multi-share declares the wildcard`() {
        assertEquals("*/*", sharedMimeType(emptyList()))
    }

    @Test
    fun `a csv shares as csv unless the provider says something more specific`() {
        assertEquals("text/csv", shareMimeType(DocumentFormat.CSV, "text/plain"))
        assertEquals("text/csv", shareMimeType(DocumentFormat.CSV, null))
        assertEquals("text/tab-separated-values", shareMimeType(DocumentFormat.CSV, "text/tab-separated-values"))
    }

    private val declared = mapOf(
        DocumentFormat.PDF to "application/pdf",
        DocumentFormat.MARKDOWN to "text/markdown",
        DocumentFormat.TEXT to "text/plain",
        DocumentFormat.CSV to "text/csv",
        DocumentFormat.DOC to "application/msword",
        DocumentFormat.DOCX to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        DocumentFormat.ODT to "application/vnd.oasis.opendocument.text",
        DocumentFormat.EPUB to "application/epub+zip",
        DocumentFormat.CODE to null,
        DocumentFormat.PPTX to null,
        DocumentFormat.UNKNOWN to null,
    )

    @Test
    fun `the table covers every format`() {
        assertEquals(DocumentFormat.entries.toSet(), declared.keys)
    }

    @Test
    fun `a placeholder or missing type shares as the format's own type, or the wildcard`() {
        val placeholders = listOf(
            null, "", "   ", "\t\n", "application/octet-stream", "application/binary", "*/*",
            " application/octet-stream ", " */* ",
        )
        for ((format, own) in declared) {
            for (reported in placeholders) {
                assertEquals("$format reported as '$reported'", own ?: "*/*", shareMimeType(format, reported))
            }
        }
    }

    @Test
    fun `a text-plain label is a mislabel for every format that is not plain text`() {
        for ((format, own) in declared) {
            val expected = when (own) {
                null, "text/plain" -> "text/plain"
                else -> own
            }
            assertEquals("$format reported as text/plain", expected, shareMimeType(format, "text/plain"))
            assertEquals("$format reported as padded text/plain", expected, shareMimeType(format, "  text/plain "))
        }
    }

    @Test
    fun `a specific type other than text-plain is kept for every format`() {
        val specific = listOf("application/pdf", "image/png", "application/x-custom", "text/html", "text/csv")
        for (format in DocumentFormat.entries) {
            for (reported in specific) {
                assertEquals("$format reported as $reported", reported, shareMimeType(format, reported))
            }
        }
    }

    @Test
    fun `a mislabel is not repaired by case`() {
        // The provider's answer is compared as written: only the exact placeholder spellings are placeholders.
        assertEquals("Text/Plain", shareMimeType(DocumentFormat.MARKDOWN, "Text/Plain"))
        assertEquals("APPLICATION/OCTET-STREAM", shareMimeType(DocumentFormat.PDF, "APPLICATION/OCTET-STREAM"))
    }

    private fun selection(vararg files: Pair<DocumentFormat, String?>): String =
        sharedMimeType(files.map { (format, reported) -> shareMimeType(format, reported) })

    @Test
    fun `a mixed selection narrows only when every file resolves to the same type`() {
        assertEquals(
            "application/pdf",
            selection(
                DocumentFormat.PDF to "application/pdf",
                DocumentFormat.PDF to "application/octet-stream",
                DocumentFormat.PDF to null,
            ),
        )
        assertEquals(
            "text/markdown",
            selection(DocumentFormat.MARKDOWN to "text/plain", DocumentFormat.MARKDOWN to "text/markdown"),
        )
        assertEquals("text/plain", selection(DocumentFormat.TEXT to "text/plain", DocumentFormat.CODE to "text/plain"))
    }

    @Test
    fun `a selection of different types declares the wildcard`() {
        assertEquals("*/*", selection(DocumentFormat.PDF to null, DocumentFormat.DOCX to null))
        assertEquals("*/*", selection(DocumentFormat.TEXT to "text/plain", DocumentFormat.MARKDOWN to "text/plain"))
        // A file with nothing to say about itself is the wildcard, which no other type equals.
        assertEquals("*/*", selection(DocumentFormat.PDF to "application/pdf", DocumentFormat.UNKNOWN to null))
        assertEquals("*/*", selection(DocumentFormat.UNKNOWN to null, DocumentFormat.CODE to null))
    }

    @Test
    fun `a selection of unidentifiable files is the wildcard, which is also what it declares`() {
        assertEquals("*/*", selection(DocumentFormat.UNKNOWN to null, DocumentFormat.UNKNOWN to "application/octet-stream"))
    }

    @Test
    fun `the order of a selection does not change what it declares`() {
        val files = listOf(
            DocumentFormat.PDF to "application/pdf",
            DocumentFormat.PDF to null,
            DocumentFormat.DOCX to "application/octet-stream",
        )
        val forward = selection(*files.toTypedArray())
        val backward = selection(*files.reversed().toTypedArray())

        assertEquals("*/*", forward)
        assertEquals(forward, backward)
    }
}
