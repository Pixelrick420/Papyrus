package com.papyrus.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsTest : E2eTest() {

    private val app = AppDriver()

    private val load = AppDriver.LOAD_TIMEOUT_MS

    @Test
    fun aChosenThemeIsShownAndKept() {
        openSettings()

        app.tap(app.text("Theme"))
        app.tap(app.text("Dark"))
        app.assertGone(app.text("Cancel"))
        app.assertShown(app.text("Dark"))

        // Leaving the screen and coming back finds the choice where it was left.
        app.tap(app.desc("Back"))
        app.tap(app.desc("Settings"))
        app.assertShown(app.text("Dark"))
    }

    @Test
    fun prettyPrintCanBeSwitchedOff() {
        openSettings()
        app.scrollToAndTap(app.text("Pretty print structured files"))
        app.tap(app.desc("Back"))

        app.openFromAnotherApp(Fixtures.text("config.json", JSON))

        app.assertShown(app.label("\"name\":\"papyrus\""), load)
        app.assertGone(app.label("\"name\": \"papyrus\""))
    }

    @Test
    fun tabSizeSetsTheIndentOfPrettyPrintedFiles() {
        openSettings()
        app.scrollToAndTap(app.text("Tab size"))
        app.tap(app.text("4 spaces"))
        app.assertGone(app.text("Cancel"))
        app.tap(app.desc("Back"))

        app.openFromAnotherApp(Fixtures.text("config.json", JSON))

        app.assertShown(app.label("{\n    \"name\": \"papyrus\""), load)
    }

    @Test
    fun autosaveExternalKeepsADocumentWithoutBeingAsked() {
        openSettings()
        app.scrollToAndTap(app.text("Autosave external docs"))
        app.tap(app.desc("Back"))

        app.openFromAnotherApp(Fixtures.text("notes.txt", "Hello from another app"))
        app.assertShown(app.label("Hello from another app"), load)

        app.launchHome()
        app.assertShown(app.text("notes.txt"), load)
    }

    private fun openSettings() {
        app.launchHome()
        app.tap(app.desc("Settings"))
    }

    private companion object {
        const val JSON = """{"name":"papyrus","kind":"viewer"}"""
    }
}
