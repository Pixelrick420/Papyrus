package io.github.pixelrick420.papyrus.viewer.pptx

import org.junit.Assert.assertEquals
import org.junit.Test

class SlideSizingTest {

    private val widescreen = SceneSize(12_192_000.0, 6_858_000.0)

    @Test
    fun `the aspect ratio is the slide size's height over its width`() {
        assertEquals(6_858_000f / 12_192_000f, slideAspectRatio(widescreen), 1e-6f)
    }

    @Test
    fun `a degenerate slide size has no aspect ratio`() {
        assertEquals(0f, slideAspectRatio(SceneSize(0.0, 6_858_000.0)), 0f)
    }

    @Test
    fun `the bitmap height is the width scaled by the slide ratio`() {
        assertEquals(720, slideHeightPx(1280, widescreen, 16_384))
        assertEquals(960, slideHeightPx(1280, SceneSize(12_192_000.0, 9_144_000.0), 16_384))
    }

    @Test
    fun `the bitmap height is capped so a degenerate slide cannot allocate a huge bitmap`() {
        assertEquals(100, slideHeightPx(1280, SceneSize(1.0, 100.0), 100))
    }
}
