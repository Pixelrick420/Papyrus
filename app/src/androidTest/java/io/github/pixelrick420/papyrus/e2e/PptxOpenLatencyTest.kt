package io.github.pixelrick420.papyrus.e2e

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import io.github.pixelrick420.papyrus.viewer.Type
import io.github.pixelrick420.papyrus.viewer.pptx
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PptxOpenLatencyTest : E2eTest() {

    private val app = AppDriver()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val load = AppDriver.LOAD_TIMEOUT_MS

    @Test
    fun libraryTapToFirstSlideBitmap() {
        val name = "latency-deck.pptx"
        val deck = pptx {
            repeat(SLIDES) { index ->
                slide {
                    textPlaceholder(Type.TITLE, idx = 0, x = TITLE_X, y = TITLE_Y, w = TITLE_W, h = TITLE_H) {
                        para("Slide ${index + 1}")
                    }
                    textPlaceholder(Type.BODY, idx = 1, x = BODY_X, y = BODY_Y, w = BODY_W, h = BODY_H) {
                        para("Measured body text for slide ${index + 1}.")
                    }
                }
            }
        }
        app.addToLibrary(Fixtures.bytes(name, deck.toBytes()))

        repeat(RUNS) { run ->
            app.launchHome()
            app.assertShown(app.text(name), load)
            val elapsed = timeTapUntilShown(app.text(name), app.descStartsWith("Slide 1 of $SLIDES"), load)
            Log.i(TAG, "PPTX_OPEN_FIRST_SLIDE_MS run=$run value=$elapsed")
        }
    }

    private fun timeTapUntilShown(target: BySelector, expected: BySelector, timeoutMs: Long): Long {
        val start = SystemClock.uptimeMillis()
        app.tap(target)
        val deadline = start + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (device.findObject(expected) != null) return SystemClock.uptimeMillis() - start
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("Nothing matched $expected within ${timeoutMs}ms. On screen: ${app.onScreen()}")
    }

    private companion object {
        const val TAG = "PptxOpenLatency"
        const val SLIDES = 60
        const val RUNS = 4
        const val POLL_MS = 10L
        const val TITLE_X = 838_200L
        const val TITLE_Y = 365_125L
        const val TITLE_W = 10_515_600L
        const val TITLE_H = 1_325_563L
        const val BODY_X = 838_200L
        const val BODY_Y = 2_000_000L
        const val BODY_W = 10_515_600L
        const val BODY_H = 3_000_000L
    }
}
