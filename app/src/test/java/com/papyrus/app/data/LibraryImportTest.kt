package com.papyrus.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** The name chain and the copy's failure contract: no partial file ever survives a failed copy. */
class LibraryImportTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `the display name wins and keeps its own extension`() {
        assertEquals("Report.pdf", LibraryImport.importFileName("Report.pdf", "other", "application/pdf", 1L))
    }

    @Test
    fun `a name without an extension gains one from the mime type`() {
        assertEquals("notes.txt", LibraryImport.importFileName("notes", null, "text/plain", 1L))
    }

    @Test
    fun `the uri segment is used when the display name is missing`() {
        assertEquals("scan.pdf", LibraryImport.importFileName(null, "scan.pdf", null, 1L))
    }

    @Test
    fun `a blank display name falls through to the segment`() {
        assertEquals("scan.pdf", LibraryImport.importFileName("   ", "scan.pdf", null, 1L))
    }

    @Test
    fun `nothing usable becomes a timestamped name with the mime extension`() {
        assertEquals(
            "imported-1700000000000.pdf",
            LibraryImport.importFileName(null, null, "application/pdf", 1_700_000_000_000L),
        )
    }

    @Test
    fun `an unknown or absent mime type invents no extension`() {
        assertEquals("imported-42", LibraryImport.importFileName(null, null, null, 42L))
        assertEquals("imported-42", LibraryImport.importFileName(null, null, "application/x-custom", 42L))
    }

    @Test
    fun `path separators are neutralised so the name cannot escape imports`() {
        val forward = LibraryImport.importFileName("../evil.pdf", null, null, 1L)
        assertEquals(".. evil.pdf", forward)
        assertFalse(forward.contains("/"))
        assertFalse(forward.contains("\\"))
        assertEquals("a b.pdf", LibraryImport.importFileName("a\\b.pdf", null, null, 1L))
    }

    @Test
    fun `a bare dot or dotdot name falls through to the timestamp`() {
        assertEquals("imported-7", LibraryImport.importFileName("..", null, null, 7L))
        assertEquals("imported-7", LibraryImport.importFileName(".", null, null, 7L))
    }

    @Test
    fun `a trailing dot is not an extension`() {
        assertEquals("report.pdf", LibraryImport.importFileName("report.", null, "application/pdf", 1L))
    }

    @Test
    fun `a free name is returned unchanged`() {
        assertEquals("report.pdf", LibraryImport.uniqueName("report.pdf", emptySet()))
    }

    @Test
    fun `the first collision gains a numbered suffix`() {
        assertEquals("report (1).pdf", LibraryImport.uniqueName("report.pdf", setOf("report.pdf")))
    }

    @Test
    fun `collisions count up until a free name`() {
        val existing = setOf("report.pdf", "report (1).pdf")
        assertEquals("report (2).pdf", LibraryImport.uniqueName("report.pdf", existing))
    }

    @Test
    fun `a name without an extension still collides correctly`() {
        assertEquals("notes (1)", LibraryImport.uniqueName("notes", setOf("notes")))
    }

    @Test
    fun `a leading dot does not read as an extension when numbering`() {
        assertEquals(".gitignore (1)", LibraryImport.uniqueName(".gitignore", setOf(".gitignore")))
    }

    @Test
    fun `a successful copy writes the same bytes and leaves only the target`() {
        val dir = temp.newFolder("imports")
        val target = File(dir, "doc.pdf")
        val bytes = "hello papyrus".toByteArray()

        val result = LibraryImport.copyStream({ ByteArrayInputStream(bytes) }, target)

        assertEquals(target, result)
        assertArrayEquals(bytes, target.readBytes())
        assertEquals(listOf("doc.pdf"), dir.list()?.toList())
    }

    @Test
    fun `a failure mid-copy leaves neither the target nor the temp`() {
        val dir = temp.newFolder("imports")
        val target = File(dir, "doc.pdf")

        assertThrows(IOException::class.java) {
            LibraryImport.copyStream({ oneByteThenFails() }, target)
        }

        assertFalse(target.exists())
        assertEquals(emptyList<String>(), dir.list()?.toList())
    }

    @Test
    fun `a file directly inside the imports dir is owned`() {
        val dir = temp.newFolder("imports")
        val file = File(dir, "doc.pdf").apply { createNewFile() }
        assertTrue(LibraryImport.isInside(dir, file))
    }

    @Test
    fun `a sibling directory sharing the prefix is not owned`() {
        val dir = temp.newFolder("imports")
        val file = File(temp.newFolder("imports-evil"), "doc.pdf").apply { createNewFile() }
        assertFalse(LibraryImport.isInside(dir, file))
    }

    /** One byte, then failure, so the partial write path is exercised rather than a clean open. */
    private fun oneByteThenFails(): InputStream = object : InputStream() {
        private var reads = 0
        override fun read(): Int = if (reads++ == 0) 'x'.code else throw IOException("boom")
    }
}
