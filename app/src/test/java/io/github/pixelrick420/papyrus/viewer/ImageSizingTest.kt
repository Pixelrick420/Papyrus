package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.ImageBounds
import io.github.pixelrick420.papyrus.viewer.pptx.ImageSizing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSizingTest {

    @Test
    fun `sample size halves until the target width is met`() {
        assertEquals(1, ImageSizing.sampleSize(1_000, 1_000))
        assertEquals(1, ImageSizing.sampleSize(1_000, 600))
        assertEquals(2, ImageSizing.sampleSize(1_000, 400))
        assertEquals(4, ImageSizing.sampleSize(1_000, 200))
        assertEquals(8, ImageSizing.sampleSize(1_000, 100))
        assertEquals(512, ImageSizing.sampleSize(1_000, 1))
    }

    @Test
    fun `a degenerate size or target never samples`() {
        assertEquals(1, ImageSizing.sampleSize(0, 100))
        assertEquals(1, ImageSizing.sampleSize(100, 0))
    }

    @Test
    fun `the 64 megapixel cap accepts an image exactly on it`() {
        assertTrue(ImageSizing.withinCap(ImageBounds(8_000, 8_000), 64_000_000))
        assertTrue(ImageSizing.withinCap(ImageBounds(16_000, 4_000), 64_000_000))
    }

    @Test
    fun `an image over the cap or with no pixels is refused`() {
        assertFalse(ImageSizing.withinCap(ImageBounds(20_000, 10_000), 64_000_000))
        assertFalse(ImageSizing.withinCap(ImageBounds(0, 0), 64_000_000))
    }

    @Test
    fun `pixels is the product in long, so a large image does not overflow`() {
        assertEquals(4_000_000_000L, ImageBounds(100_000, 40_000).pixels)
    }
}
