package com.papyrus.app.e2e

import android.content.Intent
import android.content.IntentFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Crash reports kept on the device: listing, opening, sharing and deleting one, and what opening it offers. */
@RunWith(AndroidJUnit4::class)
class CrashReportsTest : E2eTest() {

    private val app = AppDriver()

    private val load = AppDriver.LOAD_TIMEOUT_MS

    @Test
    fun aReportOpensToItsContentsAndOffersNothingButShare() {
        CrashReports.write(REPORT, MARKER)
        openCrashReports()
        app.assertShown(app.textContains(REPORT))

        app.tapUntilShown(app.textContains(REPORT), app.label(MARKER), load)

        app.assertShown(app.desc("Share"))
        // No overflow menu: a report cannot be saved to the library, bookmarked, searched or read aloud.
        app.assertGone(app.desc("More options"))

        app.back()
        app.assertShown(app.textContains(REPORT))
    }

    @Test
    fun sharingAReportOpensTheChooser() {
        CrashReports.write(REPORT, MARKER)
        openCrashReports()
        app.tapUntilShown(app.textContains(REPORT), app.label(MARKER), load)

        // The chooser is the system's own screen: the launch is caught and held back, so none is left on the device.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val chooser = instrumentation.addMonitor(IntentFilter(Intent.ACTION_CHOOSER), null, true)
        try {
            app.tap(app.desc("Share"))
            assertTrue("Share did not open a chooser", Polling.until(AppDriver.UI_TIMEOUT_MS) { chooser.hits > 0 })
        } finally {
            instrumentation.removeMonitor(chooser)
        }
    }

    @Test
    fun deletingAReportAsksFirstAndThenRemovesIt() {
        val report = CrashReports.write(REPORT, MARKER)
        openCrashReports()

        app.tap(app.desc("Delete"))
        app.assertShown(app.text("Delete crash report?"))
        app.tap(app.text("Cancel"))
        app.assertGone(app.text("Delete crash report?"))
        // Cancelled: the report is still listed and still on disk.
        app.assertShown(app.textContains(REPORT))
        assertTrue("Cancel deleted the report", report.exists())

        app.tap(app.desc("Delete"))
        app.assertShown(app.text("Delete crash report?"))
        app.tap(app.text("Delete"))

        app.assertShown(app.text("No crash reports"))
        assertFalse("The report is still on disk", report.exists())
    }

    private fun openCrashReports() {
        app.launchHome()
        app.tap(app.desc("Settings"))
        app.scrollToAndTap(app.textContains("manage crash logs"))
        app.assertShown(app.text("Crash reports"))
    }

    private companion object {
        const val REPORT = "crash-1700000000000-e2e.json"
        const val MARKER = "e2e-device"
    }
}
