package com.papyrus.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The library: saving a document into it, then finding, ordering and removing what is there. */
@RunWith(AndroidJUnit4::class)
class LibraryTest {

    private val app = AppDriver()

    private val load = AppDriver.LOAD_TIMEOUT_MS

    @Test
    fun savingAHandedOverDocumentAddsItToTheLibrary() {
        app.openFromAnotherApp(Fixtures.text("notes.txt", "Hello from another app"))
        app.assertShown(app.label("Hello from another app"), load)

        app.tap(app.desc("More options"))
        app.assertShown(app.text("Save to library"))
        // There is no library row to hang a bookmark from until it is saved.
        app.assertGone(app.text("Add bookmark"))
        app.tap(app.text("Save to library"))
        app.assertShown(app.text("Saved to your library"))

        app.tap(app.desc("More options"))
        app.assertShown(app.text("Add bookmark"))
        app.assertGone(app.text("Save to library"))

        app.launchHome()
        app.assertShown(app.text("notes.txt"))
        app.assertGone(app.text("No documents yet"))
    }

    @Test
    fun aLibraryDocumentOpensFromHomeAndBackReturnsToHome() {
        app.addToLibrary(Fixtures.text("notes.txt", "Hello from the library"))
        app.launchHome()

        app.tap(app.text("notes.txt"))
        app.assertShown(app.label("Hello from the library"), load)
        app.assertGone(app.desc("Add files"))

        app.back()
        app.assertShown(app.desc("Add files"))
        app.assertShown(app.text("notes.txt"))
    }

    @Test
    fun aBookmarkIsAddedListedAndDeleted() {
        app.addToLibrary(Fixtures.text("notes.txt", "Hello from the library"))

        app.chooseFromViewerMenu("Add bookmark")
        app.assertShown(app.text("Bookmark added"))
        app.chooseFromViewerMenu("Bookmarks")
        app.tap(app.descStartsWith("Delete bookmark"))

        app.assertShown(app.text("Bookmark deleted"))
        app.assertShown(app.text("No bookmarks yet"))
    }

    @Test
    fun searchNarrowsTheListByName() {
        app.addToLibrary(Fixtures.text("alpha.txt", "first"))
        app.addToLibrary(Fixtures.text("beta.txt", "second"))
        app.launchHome()
        app.assertShown(app.text("alpha.txt"))
        app.assertShown(app.text("beta.txt"))

        app.typeInto(app.field(), "alp")
        app.assertShown(app.text("alpha.txt"))
        app.assertGone(app.text("beta.txt"))

        app.typeInto(app.field(), "zzzz")
        app.assertShown(app.textContains("No files match"))

        app.tap(app.desc("Clear search"))
        app.assertShown(app.text("alpha.txt"))
        app.assertShown(app.text("beta.txt"))
    }

    @Test
    fun searchFindsTextInsideADocument() {
        app.addToLibrary(Fixtures.text("journal.txt", "Spotted a zebrafish near the pier."))
        app.addToLibrary(Fixtures.text("other.txt", "Nothing relevant here."))
        app.launchHome()

        app.typeInto(app.field(), "zebrafish")

        // The words are in no file name, so a row is there only once the index has caught up. The label is the
        // snippet under the row, not the field that is holding the same word.
        app.assertShown(app.label("zebrafish"), load)
        app.assertShown(app.text("journal.txt"))
        app.assertGone(app.text("other.txt"))

        app.tap(app.text("journal.txt"))
        app.assertShown(app.label("Spotted a zebrafish near the pier."), load)
        app.assertGone(app.desc("Add files"))
    }

    @Test
    fun sortOrdersTheList() {
        app.addToLibrary(Fixtures.text("alpha.txt", "first"))
        app.addToLibrary(Fixtures.text("bravo.txt", "second"))
        app.launchHome()
        // Last opened first, so the one saved last leads.
        assertAbove("bravo.txt", "alpha.txt")

        chooseInSortAndFilter("A to Z")
        assertAbove("alpha.txt", "bravo.txt")

        chooseInSortAndFilter("Z to A")
        assertAbove("bravo.txt", "alpha.txt")
    }

    @Test
    fun aTypeFilterNarrowsTheListAndClearsAgain() {
        app.addToLibrary(Fixtures.text("notes.txt", "plain words"))
        app.addToLibrary(Fixtures.text("readme.md", "# Title"))
        app.launchHome()

        chooseInSortAndFilter("MD")
        app.assertShown(app.text("readme.md"))
        app.assertGone(app.text("notes.txt"))

        chooseInSortAndFilter("Clear")
        app.assertShown(app.text("readme.md"))
        app.assertShown(app.text("notes.txt"))
    }

    @Test
    fun aStarredDocumentIsMarkedAndFiltered() {
        app.addToLibrary(Fixtures.text("notes.txt", "first"))
        app.addToLibrary(Fixtures.text("other.txt", "second"))
        app.launchHome()

        app.openRowMenu("notes.txt")
        app.tap(app.text("Star"))
        app.assertShown(app.desc("Star"))

        chooseInSortAndFilter("Starred")
        app.assertShown(app.text("notes.txt"))
        app.assertGone(app.text("other.txt"))
    }

    @Test
    fun aRemovedDocumentCanBeTakenBack() {
        app.addToLibrary(Fixtures.text("notes.txt", "first"))
        app.launchHome()

        app.openRowMenu("notes.txt")
        app.tap(app.text("Remove"))
        app.assertShown(app.text("Removed from the list"))
        app.assertGone(app.text("notes.txt"))

        app.tap(app.text("Undo"))
        app.assertShown(app.text("notes.txt"))
    }

    @Test
    fun aRemovedDocumentStaysRemovedOnceTheUndoWindowEnds() {
        app.addToLibrary(Fixtures.text("notes.txt", "first"))
        app.launchHome()
        app.openRowMenu("notes.txt")
        app.tap(app.text("Remove"))
        app.assertShown(app.text("Removed from the list"))

        // The snackbar stays for exactly as long as the removal can still be undone.
        assertTrue("The undo window never ended", app.waitGone(app.text("Removed from the list"), UNDO_WINDOW_TIMEOUT_MS))

        app.launchHome()
        app.assertShown(app.text("No documents yet"))
    }

    @Test
    fun selectionRemovesSeveralDocumentsAtOnce() {
        app.addToLibrary(Fixtures.text("alpha.txt", "first"))
        app.addToLibrary(Fixtures.text("bravo.txt", "second"))
        app.launchHome()

        app.longPress(app.text("alpha.txt"))
        app.assertShown(app.text("1 selected"))
        app.tap(app.desc("Select all"))
        app.assertShown(app.text("2 selected"))

        app.tap(app.desc("Remove"))
        app.assertShown(app.text("Remove from list?"))
        app.assertShown(app.text("This action will remove 2 items"))
        app.tap(app.text("Remove"))

        app.assertShown(app.text("Removed 2 files"))
        app.assertShown(app.text("No documents yet"))
    }

    /** Opens the sort and filter sheet, taps [chip] in it and closes it again. */
    private fun chooseInSortAndFilter(chip: String) {
        app.tap(app.descStartsWith("Sort:"))
        app.tapLowest(app.text(chip))
        app.back()
        app.assertGone(app.text("Sort & filter"), AppDriver.UI_TIMEOUT_MS)
    }

    private fun assertAbove(upper: String, lower: String) {
        val upperY = app.find(app.text(upper)).visibleCenter.y
        val lowerY = app.find(app.text(lower)).visibleCenter.y
        assertTrue("$upper is at $upperY, below $lower at $lowerY", upperY < lowerY)
    }

    private companion object {
        /** The removal's undo window is six seconds; this is slack for a slow emulator, not a second window. */
        const val UNDO_WINDOW_TIMEOUT_MS = 20_000L
    }
}
