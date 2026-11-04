package com.papyrus.app.e2e

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.papyrus.app.MainApplication
import java.io.File

/**
 * Puts Papyrus back to a fresh install, so no test inherits another's library, settings or open document.
 * [ofRunningApp] cannot delete files under the live process, so it clears through the app's own handles.
 */
internal object FreshInstall {

    /** Clears the data of an app that has not started yet. Call before any of the app's own code runs. */
    fun beforeAppCode(context: Context) {
        clear(File(context.dataDir, DATABASES_DIR))
        clearImports(context)
        // A fresh install has no preferences at all; the app writes the setting it needs back as it reads them.
        clear(File(context.dataDir, SHARED_PREFS_DIR))
    }

    /** Clears the running app through its own handles; instrumentation shares its process. */
    fun ofRunningApp() {
        val app = ApplicationProvider.getApplicationContext<MainApplication>()
        app.database.clearAllTables()
        clearImports(app)
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
    private const val SHARED_PREFS_DIR = "shared_prefs"
}
