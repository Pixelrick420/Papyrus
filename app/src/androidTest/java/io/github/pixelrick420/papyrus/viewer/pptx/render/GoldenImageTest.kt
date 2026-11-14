package io.github.pixelrick420.papyrus.viewer.pptx.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * The raster goldens. Their references were rendered by this harness, not exported from PowerPoint, so they
 * lock the renderer against regressions rather than prove PowerPoint parity.
 *
 * Run with `-Pandroid.testInstrumentationRunnerArguments.papyrus.golden.write=true` to refresh the files in the
 * app under test's external files directory, then pull `goldens/` into `app/src/androidTest/assets/goldens/`.
 */
@RunWith(AndroidJUnit4::class)
class GoldenImageTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val write = InstrumentationRegistry.getArguments().getString(WRITE_ARGUMENT) == "true"

    @Test
    fun shapeZoo() = verify("shape-zoo")

    @Test
    fun linearGradients() = verify("gradients-linear")

    @Test
    fun radialGradients() = verify("gradients-radial")

    @Test
    fun picturesCrop() = verify("pictures-crop")

    @Test
    fun groupsRotateFlip() = verify("groups-rotate-flip")

    @Test
    fun masterLayout() = verify("master-layout")

    @Test
    fun darkTheme() = verify("dark-theme")

    @Test
    fun themeColours() = verify("theme-colours")

    @Test
    fun strokesDashes() = verify("strokes-dashes")

    @Test
    fun backgrounds() = verify("backgrounds")

    @Test
    fun presetAdjusts() = verify("preset-adjusts")

    @Test
    fun textFrames() = verify("text-frames")

    private fun verify(name: String) = runBlocking {
        val deck = GoldenDecks.all.single { it.name == name }
        val actual = SlidePngHarness.render(instrumentation.targetContext, deck.bytes, 0, WIDTH_PX)
        assertNotNull("$name rendered nothing", actual)
        actual ?: return@runBlocking
        if (write) {
            writeReference(name, actual)
            return@runBlocking
        }
        val expected = instrumentation.context.assets.open("goldens/$name.png").use {
            BitmapFactory.decodeStream(it)
        }
        assertNotNull("no reference for $name", expected)
        expected ?: return@runBlocking
        assertEquals("$name width", expected.width, actual.width)
        assertEquals("$name height", expected.height, actual.height)
        compare(name, expected, actual)
    }

    private fun compare(name: String, expected: Bitmap, actual: Bitmap) {
        val width = expected.width
        val height = expected.height
        val expectedPixels = IntArray(width * height)
        val actualPixels = IntArray(width * height)
        expected.getPixels(expectedPixels, 0, width, 0, 0, width, height)
        actual.getPixels(actualPixels, 0, width, 0, 0, width, height)
        val mismatched = BooleanArray(expectedPixels.size)
        for (index in expectedPixels.indices) {
            if (channelDistance(expectedPixels[index], actualPixels[index]) > CHANNEL_TOLERANCE) {
                mismatched[index] = true
            }
        }
        val count = mismatched.count { it }
        assertTrue("$name: $count pixels differ", count <= expectedPixels.size * MAX_MISMATCH_SHARE)
        // Thick blobs survive erosion; the one-pixel anti-aliasing fringe that a legitimate rasteriser change
        // leaves on an edge does not.
        val core = erode(mismatched, width, height)
        val largest = largestRegion(core, width, height)
        if (largest > expectedPixels.size * MAX_CORE_REGION_SHARE) {
            dump(name, actual)
            fail("$name: a connected mismatch region covers $largest pixels")
        }
    }

    private fun erode(mask: BooleanArray, width: Int, height: Int): BooleanArray {
        val core = BooleanArray(mask.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                if (mask[index] && mask[index - 1] && mask[index + 1] && mask[index - width] && mask[index + width]) {
                    core[index] = true
                }
            }
        }
        return core
    }

    private fun largestRegion(mask: BooleanArray, width: Int, height: Int): Int {
        val seen = BooleanArray(mask.size)
        val queue = IntArray(mask.size)
        var largest = 0
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            seen[start] = true
            var size = 0
            fun enqueue(next: Int) {
                if (next >= 0 && mask[next] && !seen[next]) {
                    seen[next] = true
                    queue[tail++] = next
                }
            }
            while (head < tail) {
                val index = queue[head++]
                size++
                val x = index % width
                if (x > 0) enqueue(index - 1)
                if (x < width - 1) enqueue(index + 1)
                if (index >= width) enqueue(index - width)
                if (index + width < mask.size) enqueue(index + width)
            }
            largest = max(largest, size)
        }
        return largest
    }

    private fun channelDistance(first: Int, second: Int): Int {
        val alpha = abs(((first ushr 24) and 0xFF) - ((second ushr 24) and 0xFF))
        val red = abs(((first ushr 16) and 0xFF) - ((second ushr 16) and 0xFF))
        val green = abs(((first ushr 8) and 0xFF) - ((second ushr 8) and 0xFF))
        val blue = abs((first and 0xFF) - (second and 0xFF))
        return max(max(alpha, red), max(green, blue))
    }

    private fun writeReference(name: String, bitmap: Bitmap) {
        val file = File(goldenDir(), "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("$name reference is ${file.length()} bytes, over $MAX_REFERENCE_BYTES", file.length() <= MAX_REFERENCE_BYTES)
    }

    private fun dump(name: String, bitmap: Bitmap) {
        runCatching {
            File(goldenDir(), "actual-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    private fun goldenDir(): File {
        // The test APK's own data directory belongs to another UID. Some emulator images also hand back a
        // null external directory, so fall back to the app's internal one.
        val app = instrumentation.targetContext
        val root = app.getExternalFilesDir(null) ?: app.filesDir
        return File(root, "goldens").apply { mkdirs() }
    }

    private companion object {
        const val WIDTH_PX = 1280
        const val CHANNEL_TOLERANCE = 12
        const val MAX_MISMATCH_SHARE = 0.03
        const val MAX_CORE_REGION_SHARE = 0.001
        const val MAX_REFERENCE_BYTES = 100L * 1024L
        const val WRITE_ARGUMENT = "papyrus.golden.write"
    }
}
