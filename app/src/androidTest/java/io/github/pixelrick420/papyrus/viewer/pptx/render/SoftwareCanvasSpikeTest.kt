package io.github.pixelrick420.papyrus.viewer.pptx.render

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spike S5: are software-canvas blur shadows and gradient shaders good and fast enough on a low-end device?
 *
 * Pass criterion: the sampled pixels match the analytic gradient and show a blur-shadow ramp, and a
 * 640x480 frame with both effects averages under [BUDGET_MS]. Run it on the target device with
 * `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.pixelrick420.papyrus.viewer.pptx.render.SoftwareCanvasSpikeTest`
 * and read the `S5` line in logcat for the measured time. The change that added this test ran on a machine
 * with no device attached, so this KDoc records the criteria rather than a measured result.
 */
@RunWith(AndroidJUnit4::class)
class SoftwareCanvasSpikeTest {

    @Test
    fun gradients_interpolate_between_their_stops() {
        val red = Rgba(255, 0, 0)
        val blue = Rgba(0, 0, 255)
        val linear = DisplayList(
            listOf(
                DrawOp.FillPath(
                    PathData.rect(RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())),
                    PaintSpec.Linear(PointF(0f, 0f), PointF(WIDTH.toFloat(), 0f), listOf(ColorStop(0f, red), ColorStop(1f, blue))),
                ),
            ),
        )

        val bitmap = SoftwareCanvas.render(linear, WIDTH, HEIGHT)

        assertColor("left stop", red, bitmap.getPixel(0, HEIGHT / 2))
        assertColor("middle", Rgba(127, 0, 128), bitmap.getPixel(WIDTH / 2, HEIGHT / 2))
        assertColor("right stop", blue, bitmap.getPixel(WIDTH - 1, HEIGHT / 2))
    }

    @Test
    fun a_radial_gradient_focuses_its_first_stop() {
        val red = Rgba(255, 0, 0)
        val blue = Rgba(0, 0, 255)
        val radial = DisplayList(
            listOf(
                DrawOp.FillPath(
                    PathData.rect(RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())),
                    PaintSpec.Radial(
                        PointF(WIDTH / 2f, HEIGHT / 2f),
                        WIDTH / 2f,
                        listOf(ColorStop(0f, red), ColorStop(1f, blue)),
                    ),
                ),
            ),
        )

        val bitmap = SoftwareCanvas.render(radial, WIDTH, HEIGHT)

        assertColor("centre", red, bitmap.getPixel(WIDTH / 2, HEIGHT / 2))
        assertColor("edge", blue, bitmap.getPixel(1, HEIGHT / 2))
    }

    @Test
    fun a_blur_shadow_produces_an_alpha_ramp() {
        val shadowed = DisplayList(
            listOf(
                DrawOp.Shadow(ShadowSpec(Rgba.BLACK, blur = 12f, dx = 0f, dy = 10f)),
                DrawOp.FillPath(
                    PathData.rect(RectF(20f, 20f, 120f, 80f)),
                    PaintSpec.Solid(Rgba.BLACK),
                ),
            ),
        )

        val bitmap = SoftwareCanvas.render(shadowed, WIDTH, HEIGHT)

        assertEquals("shape is opaque", 255, Color.alpha(bitmap.getPixel(70, 50)))
        val ramp = Color.alpha(bitmap.getPixel(70, 90))
        assertTrue("shadow ramp alpha was $ramp", ramp in 1..254)
        assertEquals("untouched corner", 0, Color.alpha(bitmap.getPixel(400, 400)))
    }

    @Test
    fun a_640_by_480_frame_stays_within_the_software_budget() {
        val frame = effectsFrame()
        repeat(WARMUP) { SoftwareCanvas.render(frame, WIDTH, HEIGHT) }
        val start = System.nanoTime()
        repeat(FRAMES) { SoftwareCanvas.render(frame, WIDTH, HEIGHT) }
        val averageMs = (System.nanoTime() - start) / 1_000_000.0 / FRAMES

        println("S5 software canvas 640x480 with gradients and blur: ${averageMs}ms per frame")
        assertTrue("S5 average was ${averageMs}ms", averageMs < BUDGET_MS)
    }

    private fun effectsFrame(): DisplayList {
        val red = Rgba(255, 0, 0)
        val blue = Rgba(0, 0, 255)
        return DisplayList(
            listOf(
                DrawOp.FillPath(
                    PathData.rect(RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat())),
                    PaintSpec.Linear(PointF(0f, 0f), PointF(WIDTH.toFloat(), 0f), listOf(ColorStop(0f, red), ColorStop(1f, blue))),
                ),
                DrawOp.Shadow(ShadowSpec(Rgba.BLACK, blur = 12f, dx = 4f, dy = 8f)),
                DrawOp.FillPath(
                    PathData.rect(RectF(40f, 40f, 280f, 200f)),
                    PaintSpec.Radial(
                        PointF(160f, 120f),
                        160f,
                        listOf(ColorStop(0f, Rgba.WHITE), ColorStop(1f, Rgba(0x22, 0x22, 0x22))),
                    ),
                ),
                DrawOp.DrawGlyphRun(
                    GlyphRun("S5 shadow", 60f, 260f, PaintSpec.Solid(Rgba.BLACK), size = 28f, bold = true),
                ),
            ),
        )
    }

    private fun assertColor(what: String, expected: Rgba, actual: Int) {
        assertEquals("$what red", expected.red.toDouble(), Color.red(actual).toDouble(), TOLERANCE)
        assertEquals("$what green", expected.green.toDouble(), Color.green(actual).toDouble(), TOLERANCE)
        assertEquals("$what blue", expected.blue.toDouble(), Color.blue(actual).toDouble(), TOLERANCE)
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
        const val WARMUP = 3
        const val FRAMES = 10
        const val BUDGET_MS = 50.0
        const val TOLERANCE = 2.0
    }
}
