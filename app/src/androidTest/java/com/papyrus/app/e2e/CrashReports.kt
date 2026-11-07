package com.papyrus.app.e2e

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/**
 * Crash reports as the app's own reporter leaves them: a JSON file in `filesDir/crash-reports`, named by the
 * time it was written. A test cannot crash the app to get one, so it writes the file the crash would have.
 */
internal object CrashReports {

    private val folder: File
        get() = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "crash-reports")

    /** Writes a report called [name] whose product field holds [marker], so its contents can be told on screen. */
    fun write(name: String, marker: String): File {
        folder.mkdirs()
        val report = File(folder, name)
        report.writeText("""{"REPORT_ID":"e2e","PRODUCT":"$marker","STACK_TRACE":"java.lang.IllegalStateException: boom"}""")
        return report
    }
}
