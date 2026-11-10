package com.papyrus.app.crash

import com.papyrus.app.data.DocumentEntity
import com.papyrus.app.data.DocumentFormat
import com.papyrus.app.ui.navigation.Routes
import com.papyrus.app.ui.screens.ViewerUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashReportStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `list returns only json files sorted descending by name`() {
        val dir = temp.newFolder("crash-reports")
        File(dir, "crash-100-a.json").createNewFile()
        File(dir, "crash-300-c.json").createNewFile()
        File(dir, "crash-200-b.json").createNewFile()
        File(dir, "ignore.txt").createNewFile()
        File(dir, "another.xml").createNewFile()

        val list = CrashReportStore.list(dir)

        assertEquals(3, list.size)
        assertEquals("crash-300-c.json", list[0].name)
        assertEquals("crash-200-b.json", list[1].name)
        assertEquals("crash-100-a.json", list[2].name)
    }

    @Test
    fun `prune removes reports past MAX_REPORTS`() {
        val dir = temp.newFolder("crash-reports")
        for (i in 1..25) {
            val padded = i.toString().padStart(3, '0')
            File(dir, "crash-$padded.json").createNewFile()
        }

        assertEquals(25, CrashReportStore.list(dir).size)
        CrashReportStore.prune(dir)
        val remaining = CrashReportStore.list(dir)
        assertEquals(20, remaining.size)
        assertEquals("crash-025.json", remaining.first().name)
        assertEquals("crash-006.json", remaining.last().name)
        assertFalse(File(dir, "crash-001.json").exists())
        assertFalse(File(dir, "crash-005.json").exists())
    }

    @Test
    fun `isInside returns true only for direct children of crash dir`() {
        val dir = temp.newFolder("crash-reports")
        val file = File(dir, "crash-1.json").apply { createNewFile() }
        val siblingDir = temp.newFolder("crash-reports-evil")
        val evilFile = File(siblingDir, "crash-1.json").apply { createNewFile() }

        assertTrue(CrashReportStore.isInside(dir, file))
        assertFalse(CrashReportStore.isInside(dir, evilFile))
        assertFalse(CrashReportStore.isInside(dir, dir))
    }

    @Test
    fun `delete removes the report file`() {
        val dir = temp.newFolder("crash-reports")
        val file = File(dir, "crash-1.json").apply { createNewFile() }
        assertTrue(file.exists())
        assertTrue(CrashReportStore.delete(file))
        assertFalse(file.exists())
    }

    @Test
    fun `timestamp extracts millisecond from filename or falls back to last modified`() {
        val file = File("crash-1730000000000-some-uuid.json")
        assertEquals(1730000000000L, CrashReportStore.timestamp(file))

        val nonStandard = File("custom-report.json")
        assertEquals(nonStandard.lastModified(), CrashReportStore.timestamp(nonStandard))
    }

    @Test
    fun `routes viewer formats route with crashReport parameter`() {
        assertEquals("viewer/42", Routes.viewer(42))
        assertEquals("viewer/42?crashReport=true", Routes.viewer(42, isCrashReport = true))
    }

    @Test
    fun `crash report in ViewerUiState disables bookmarks and highlights`() {
        val doc = DocumentEntity(
            id = -1000000L,
            uri = "file:///data/user/0/com.papyrus.app/files/crash-reports/crash-1.json",
            title = "crash-1.json",
            format = DocumentFormat.CODE,
            sizeBytes = 100L,
            createdAt = 1000L,
            lastOpenedAt = 1000L,
        )
        val state = ViewerUiState(
            document = doc,
            isCrashReport = true,
            canSaveToLibrary = false,
        )
        assertFalse(state.bookmarksAvailable)
        assertFalse(state.highlightsAvailable)
        assertFalse(state.canSaveToLibrary)
    }
}
