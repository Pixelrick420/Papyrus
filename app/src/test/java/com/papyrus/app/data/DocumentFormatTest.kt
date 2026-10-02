package com.papyrus.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Detection drives which viewer opens a file; a mistake means the file will not open. */
class DocumentFormatTest {

    @Test
    fun `a known extension wins over a misleading mime type`() {
        // SAF providers routinely report a .md as octet-stream, and a .pdf as text/plain.
        assertEquals(DocumentFormat.MARKDOWN, DocumentFormat.detect("notes.md", "application/octet-stream"))
        assertEquals(DocumentFormat.PDF, DocumentFormat.detect("report.pdf", "text/plain"))
        assertEquals(DocumentFormat.DOCX, DocumentFormat.detect("cv.docx", "application/octet-stream"))
    }

    @Test
    fun `extensions and names are matched case-insensitively`() {
        assertEquals(DocumentFormat.PDF, DocumentFormat.detect("REPORT.PDF", null))
        assertEquals(DocumentFormat.MARKDOWN, DocumentFormat.detect("README.MARKDOWN", null))
    }

    @Test
    fun `an extension-less file whose name is the format resolves to CODE`() {
        assertEquals(DocumentFormat.CODE, DocumentFormat.detect("Makefile", null))
        assertEquals(DocumentFormat.CODE, DocumentFormat.detect("Dockerfile", null))
        assertEquals(DocumentFormat.CODE, DocumentFormat.detect("makefile", "text/plain"))
    }

    @Test
    fun `a dotfile is detected by its extension`() {
        // ".gitignore" has no basename, so everything after the dot is the extension.
        assertEquals(DocumentFormat.CODE, DocumentFormat.detect(".gitignore", null))
    }

    @Test
    fun `only the final extension is considered`() {
        // Regression: report.conf.txt is text, not config, or the viewer switches to monospace.
        assertEquals(DocumentFormat.TEXT, DocumentFormat.detect("report.conf.txt", null))
        assertEquals(DocumentFormat.TEXT, DocumentFormat.detect("backup.tar.gz.txt", null))
    }

    @Test
    fun `mime type decides when the name says nothing`() {
        assertEquals(DocumentFormat.PDF, DocumentFormat.detect("blob", "application/pdf"))
        assertEquals(DocumentFormat.ODT, DocumentFormat.detect("blob", "application/vnd.oasis.opendocument.text"))
        assertEquals(DocumentFormat.TEXT, DocumentFormat.detect("notes", "text/plain"))
    }

    @Test
    fun `mime matching is case-insensitive`() {
        assertEquals(DocumentFormat.PDF, DocumentFormat.detect("blob", "APPLICATION/PDF"))
    }

    @Test
    fun `nothing recognisable is UNKNOWN so the sniffer can decide`() {
        // DocumentRepository sniffs the bytes from UNKNOWN, so CODE here would break a .zip.
        assertEquals(DocumentFormat.UNKNOWN, DocumentFormat.detect("mystery", "application/octet-stream"))
        assertEquals(DocumentFormat.UNKNOWN, DocumentFormat.detect("archive.zip", null))
        assertEquals(DocumentFormat.UNKNOWN, DocumentFormat.detect("sheet.xlsx", null))
        assertEquals(DocumentFormat.UNKNOWN, DocumentFormat.detect(null, null))
        assertEquals(DocumentFormat.UNKNOWN, DocumentFormat.detect("", ""))
    }

    @Test
    fun `a trailing dot is not an extension`() {
        // "notes." must skip the whole-name fallback, so detection falls through to the mime type.
        assertEquals(DocumentFormat.PDF, DocumentFormat.detect("notes.", "application/pdf"))
    }

    @Test
    fun `source files are CODE rather than plain text`() {
        // CODE differs from TEXT only in the home-list label; the viewer renders both alike.
        listOf("Main.kt", "app.py", "index.ts", "run.sh", "Cargo.toml", "styles.css", "a.diff")
            .forEach { name ->
                assertEquals("expected CODE for $name", DocumentFormat.CODE, DocumentFormat.detect(name, null))
            }
    }

    @Test
    fun `every CODE extension is reachable through detect`() {
        // An extension detect never consults stays on the sniff path and gets mislabelled.
        assertEquals(
            emptyList<String>(),
            DocumentFormat.CODE.extensions.filterNot { it in DocumentFormat.detect("file.$it", null).extensions },
        )
    }

    @Test
    fun `the picker admits text and octet-stream so mislabelled files are still selectable`() {
        val types = DocumentFormat.pickerMimeTypes.toList()

        assertTrue("text/* missing", types.contains("text/*"))
        assertTrue("octet-stream missing", types.contains("application/octet-stream"))
        assertTrue("pdf missing", types.contains("application/pdf"))
        assertTrue("docx missing", types.contains(DocumentFormat.DOCX.mimeTypes.first()))
        // UNKNOWN contributes no mime types, so an entry for it would be a filter no provider has.
        assertFalse(types.contains("application/x-unknown"))
    }
}
