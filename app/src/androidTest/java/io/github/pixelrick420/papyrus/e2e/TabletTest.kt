package io.github.pixelrick420.papyrus.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TabletTest : E2eTest() {

    private val app = AppDriver()

    private val load = AppDriver.LOAD_TIMEOUT_MS

    @Before
    fun useATabletWindow() = TabletWindow.on()

    @After
    fun putTheWindowBack() = TabletWindow.off()

    @Test
    fun theRightPaneInvitesAChoiceUntilADocumentIsOpened() {
        app.addToLibrary(Fixtures.text("alpha.txt", "Alpha body text"))
        app.launchHome()
        app.assertShown(app.text("alpha.txt"))
        app.assertShown(app.text(PLACEHOLDER))
        app.assertShown(app.desc("Add files"))

        app.tapUntilShown(app.text("alpha.txt"), app.label("Alpha body text"), load)

        app.assertGone(app.text(PLACEHOLDER))
        // The list stays beside the document, with its add button, instead of giving way to it.
        app.assertShown(app.text("alpha.txt"))
        app.assertShown(app.desc("Add files"))
    }

    @Test
    fun selectingWhileADocumentIsOpenLeavesTheDocumentAlone() {
        app.addToLibrary(Fixtures.text("alpha.txt", "Alpha body text"))
        app.addToLibrary(Fixtures.text("bravo.txt", "Bravo body text"))
        app.launchHome()
        app.tapUntilShown(app.text("alpha.txt"), app.label("Alpha body text"), load)

        app.longPress(app.text("bravo.txt"))

        app.assertShown(app.text("1 selected"))
        app.assertGone(app.desc("Add files"))
        app.assertShown(app.label("Alpha body text"))
    }

    @Test
    fun backLeavesSelectionBeforeItClosesTheDocument() {
        app.addToLibrary(Fixtures.text("alpha.txt", "Alpha body text"))
        app.addToLibrary(Fixtures.text("bravo.txt", "Bravo body text"))
        app.launchHome()
        app.tapUntilShown(app.text("alpha.txt"), app.label("Alpha body text"), load)
        app.longPress(app.text("bravo.txt"))
        app.assertShown(app.text("1 selected"))

        app.back()

        app.assertGone(app.text("1 selected"))
        app.assertShown(app.desc("Add files"))
        app.assertShown(app.label("Alpha body text"))
        app.assertGone(app.text(PLACEHOLDER))
    }

    @Test
    fun backClosesTheOpenDocumentAndASecondBackLeavesTheApp() {
        app.addToLibrary(Fixtures.text("alpha.txt", "Alpha body text"))
        // Saving left the document in a task of its own; Back from Home would otherwise reveal it.
        app.leaveTheApp()
        app.launchHome()
        app.tapUntilShown(app.text("alpha.txt"), app.label("Alpha body text"), load)

        app.back()

        app.assertShown(app.text(PLACEHOLDER))
        app.assertGone(app.label("Alpha body text"))
        app.assertShown(app.text("alpha.txt"))

        app.back()

        app.assertGone(app.anything(), AppDriver.UI_TIMEOUT_MS)
    }

    private companion object {
        const val PLACEHOLDER = "Choose a document from the list to read it here"
    }
}
