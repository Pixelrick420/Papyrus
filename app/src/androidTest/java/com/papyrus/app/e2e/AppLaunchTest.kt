package com.papyrus.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchTest : E2eTest() {

    private val app = AppDriver()

    @Test
    fun aFreshInstallOpensOnAnEmptyLibrary() {
        app.launchHome()

        app.assertShown(app.text("Papyrus"))
        app.assertShown(app.text("No documents yet"))
        app.assertShown(app.text("Files you open will appear here."))
        app.assertShown(app.desc("Add files"))
        app.assertShown(app.desc("Settings"))
        app.assertShown(app.field())
    }

    @Test
    fun settingsOpensFromHomeAndBackReturnsToIt() {
        app.launchHome()

        app.tap(app.desc("Settings"))
        app.assertShown(app.text("Settings"))
        app.assertShown(app.text("Appearance"))
        app.assertShown(app.text("Theme"))
        app.scrollTo(app.text("About"))

        app.tap(app.desc("Back"))
        app.assertShown(app.text("No documents yet"))
    }

    @Test
    fun theAboutRowNamesTheInstalledVersion() {
        @Suppress("DEPRECATION")
        val version = app.context.packageManager.getPackageInfo(app.context.packageName, 0).versionName
        app.launchHome()
        app.tap(app.desc("Settings"))

        app.scrollTo(app.text("Version $version"))
    }
}
