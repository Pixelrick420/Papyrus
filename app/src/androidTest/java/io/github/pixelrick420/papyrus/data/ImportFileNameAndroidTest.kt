package io.github.pixelrick420.papyrus.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImportFileNameAndroidTest {

    @Test
    fun `a worst case CJK name creates a file in app storage`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = LibraryImport.importsDir(context).apply { mkdirs() }
        val name = LibraryImport.importFileName("中".repeat(130), null, "application/pdf", 1L)
        val file = File(dir, name)
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= MAX_FILE_BYTES)

        try {
            assertTrue(file.createNewFile())
            assertEquals(name, file.name)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a long title with an early dot keeps its extension in app storage`() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = LibraryImport.importsDir(context).apply { mkdirs() }
        val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        val name = LibraryImport.importFileName("Chapter 1. " + "a".repeat(130), null, docx, 1L)
        val file = File(dir, name)
        assertTrue(name.endsWith(".docx"))

        try {
            assertTrue(file.createNewFile())
            assertEquals(name, file.name)
        } finally {
            file.delete()
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 255
    }
}
