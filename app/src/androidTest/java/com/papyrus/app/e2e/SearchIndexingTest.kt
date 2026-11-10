package com.papyrus.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchIndexingTest : E2eTest() {

    private val app = AppDriver()

    private val load = AppDriver.LOAD_TIMEOUT_MS

    @Test
    fun aSmallImportIsSearchableWhileItsViewerIsStillOpen() {
        app.addToLibrary(Fixtures.text("journal.txt", SMALL))

        // The viewer is on screen, which keeps the gated pass from running, so only the eager pass can fill this.
        assertTrue(
            "The small file was not indexed while its viewer was open",
            Polling.until(INDEX_TIMEOUT_MS) { LibraryIndex.containing("zebrafish") == 1 },
        )
        app.assertShown(app.label(SMALL))

        app.launchHome()
        app.typeInto(app.field(), "zebrafish")
        app.assertShown(app.label("zebrafish"), load)
        app.assertShown(app.text("journal.txt"))
    }

    @Test
    fun nothingIsReadWhileSearchInsideDocumentsIsOff() {
        switchSearchInsideDocuments()

        app.addToLibrary(Fixtures.text("journal.txt", SMALL))
        // Longer than the eager pass waits for an import to settle, and than it takes to read a line.
        Thread.sleep(OFF_WINDOW_MS)
        assertEquals("A small file was indexed with the setting off", 0, LibraryIndex.indexedCount())

        // Leaving the viewer opens the gate as well; the setting still holds both passes back.
        app.launchHome()
        Thread.sleep(OFF_WINDOW_MS)
        assertEquals("A file was indexed with the setting off once the viewer closed", 0, LibraryIndex.indexedCount())
        app.typeInto(app.field(), "zebrafish")
        app.assertShown(app.textContains("No files match"))
    }

    @Test
    fun turningTheSettingBackOnReadsTheSmallFile() {
        switchSearchInsideDocuments()
        app.addToLibrary(Fixtures.text("journal.txt", SMALL))
        app.launchHome()
        Thread.sleep(OFF_WINDOW_MS)
        assertEquals(0, LibraryIndex.indexedCount())

        switchSearchInsideDocuments()

        assertTrue(
            "The small file was not indexed once the setting came back on",
            Polling.until(INDEX_TIMEOUT_MS) { LibraryIndex.containing("zebrafish") == 1 },
        )
        app.typeInto(app.field(), "zebrafish")
        app.assertShown(app.label("zebrafish"), load)
    }

    private fun switchSearchInsideDocuments() {
        app.launchHome()
        app.tap(app.desc("Settings"))
        app.scrollToAndTap(app.text("Search inside documents"))
        app.tap(app.desc("Back"))
    }

    private companion object {
        const val SMALL = "Spotted a zebrafish near the pier."

        /** The eager pass settles an import for a second, then reads it; this is slack on a slow emulator. */
        const val INDEX_TIMEOUT_MS = 20_000L

        const val OFF_WINDOW_MS = 4_000L
    }
}
