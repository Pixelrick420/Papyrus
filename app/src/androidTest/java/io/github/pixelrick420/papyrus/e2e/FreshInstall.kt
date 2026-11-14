package io.github.pixelrick420.papyrus.e2e

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.pixelrick420.papyrus.MainApplication
import java.io.File

internal object FreshInstall {

    fun beforeAppCode(context: Context) {
        clear(File(context.dataDir, DATABASES_DIR))
        clearImports(context)
        clear(File(context.filesDir, CRASH_REPORTS_DIR))
        // A fresh install has no preferences at all; the app writes the setting it needs back as it reads them.
        clear(File(context.dataDir, SHARED_PREFS_DIR))
    }

    fun ofRunningApp() {
        val app = ApplicationProvider.getApplicationContext<MainApplication>()
        app.database.clearAllTables()
        clearImports(app)
        clear(File(app.filesDir, CRASH_REPORTS_DIR))
        app.settings.resetToDefaults()
    }

    /** Empties `filesDir/imports` but keeps the folder: it is the `FileProvider` root the fixtures live under. */
    private fun clearImports(context: Context) {
        File(context.filesDir, IMPORTS_DIR).listFiles()?.forEach { clear(it) }
    }

    private fun clear(file: File) {
        // deleteRecursively reports true for a path that was not there, which is the state we want anyway.
        check(file.deleteRecursively()) { "Could not clear ${file.path}" }
    }

    private const val DATABASES_DIR = "databases"
    private const val IMPORTS_DIR = "imports"
    private const val CRASH_REPORTS_DIR = "crash-reports"
    private const val SHARED_PREFS_DIR = "shared_prefs"
}
