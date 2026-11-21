package io.github.pixelrick420.papyrus.e2e

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LargeTextFrameTest : E2eTest() {

    private val app = AppDriver()
    private val packageName = app.context.packageName
    private val lines = 50 * 1024 * 1024 / LINE_BYTES

    @Test
    fun jumpingToTheEndOfA50MbFileKeepsFramesWithinBudget() {
        app.openFromAnotherApp(Fixtures.largeText("huge.txt", lines, LINE_BYTES))
        app.assertShown(app.label("row-00000001"), AppDriver.LOAD_TIMEOUT_MS)

        app.chooseFromViewerMenu("Go to line")
        app.typeInto(app.field(), lines.toString())
        val started = SystemClock.elapsedRealtime()
        app.tap(app.text("Go"))
        app.assertShown(app.label(lastLabel()), AppDriver.LOAD_TIMEOUT_MS)
        val jumpMs = SystemClock.elapsedRealtime() - started

        val p95 = FrameBudget.percentile95Ms(packageName)
        assertTrue("Jump to the last line took ${jumpMs}ms (gfxinfo p95 ${p95}ms)", jumpMs <= JUMP_BUDGET_MS)
    }

    private fun lastLabel(): String = "row-${lines.toString().padStart(8, '0')}"

    private companion object {
        const val LINE_BYTES = 64
        const val JUMP_BUDGET_MS = 3_000L
    }
}
