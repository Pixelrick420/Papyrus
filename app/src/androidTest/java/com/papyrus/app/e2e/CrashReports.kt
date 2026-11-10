package com.papyrus.app.e2e

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

internal object CrashReports {

    private val folder: File
        get() = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "crash-reports")

    fun write(name: String, marker: String): File {
        folder.mkdirs()
        val report = File(folder, name)
        report.writeText("""{"REPORT_ID":"e2e","PRODUCT":"$marker","STACK_TRACE":"java.lang.IllegalStateException: boom"}""")
        return report
    }
}
