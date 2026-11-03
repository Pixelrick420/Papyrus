package com.papyrus.app.e2e

import android.content.Context
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import java.io.File

/**
 * Starts every test from a fresh install.
 *
 * The library, the copies it keeps in `filesDir/imports`, the database behind them and the settings in
 * SharedPreferences all outlive the process, so without this a test inherits what the one before it left
 * behind: a setting switched on saves the same document twice, a chosen sort order decides what the next
 * test sees first, and a document the previous test opened is still listed. The orchestrator (see
 * `testOptions`) runs each test in its own process, but it does not clear that data.
 *
 * [onCreate] runs before any of the app's own code, so nothing has opened the database or read the
 * preferences yet and removing the files cannot leave a stale handle behind.
 */
class PapyrusTestRunner : AndroidJUnitRunner() {

    override fun onCreate(arguments: Bundle) {
        super.onCreate(arguments)
        clearAppData(targetContext)
    }

    private fun clearAppData(context: Context) {
        // The database listing the documents, and the copies the list points at.
        clear(File(context.dataDir, "databases"))
        clear(File(context.filesDir, IMPORTS_DIR))
        // The settings, under the name SettingsRepository stores them with.
        clear(File(context.dataDir, "${SHARED_PREFS_DIR}/${SETTINGS_PREFS_FILE}"))
    }

    private fun clear(file: File) {
        check(file.deleteRecursively()) { "Could not clear ${file.path}" }
    }

    private companion object {
        const val IMPORTS_DIR = "imports"
        const val SHARED_PREFS_DIR = "shared_prefs"
        const val SETTINGS_PREFS_FILE = "papyrus_settings.xml"
    }
}
