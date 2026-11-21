package io.github.pixelrick420.papyrus.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfAspectTest {

    @Test
    fun `a normal page keeps its ratio`() {
        assertEquals(1.5f, sanitizedAspect(400f, 600f), TOLERANCE)
    }

    @Test
    fun `degenerate and non-finite ratios fall back to the default`() {
        assertEquals(DEFAULT_ASPECT, sanitizedAspect(0f, 600f), TOLERANCE)
        assertEquals(DEFAULT_ASPECT, sanitizedAspect(400f, 0f), TOLERANCE)
        assertEquals(DEFAULT_ASPECT, sanitizedAspect(Float.NaN, 600f), TOLERANCE)
        assertEquals(DEFAULT_ASPECT, sanitizedAspect(400f, -600f), TOLERANCE)
    }

    @Test
    fun `extreme ratios are clamped`() {
        assertEquals(10f, sanitizedAspect(1f, 1_000f), TOLERANCE)
        assertEquals(0.1f, sanitizedAspect(1_000f, 1f), TOLERANCE)
    }

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}
