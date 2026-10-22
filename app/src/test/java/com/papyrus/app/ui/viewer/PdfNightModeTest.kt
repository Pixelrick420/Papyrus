package com.papyrus.app.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

/** What night mode does to a page's colours: white paper reads black, black text reads white. */
class PdfNightModeTest {

    @Test
    fun `white paper reads black`() {
        assertEquals(0xFF000000.toInt(), invert(0xFFFFFFFF.toInt()))
    }

    @Test
    fun `black text reads white`() {
        assertEquals(0xFFFFFFFF.toInt(), invert(0xFF000000.toInt()))
    }

    @Test
    fun `colours swap`() {
        assertEquals(0xFF00FFFF.toInt(), invert(0xFFFF0000.toInt()))
        assertEquals(0xFFFF0000.toInt(), invert(0xFF00FFFF.toInt()))
    }

    @Test
    fun `a mid grey stays in range`() {
        assertEquals(0xFF7F7F7F.toInt(), invert(0xFF808080.toInt()))
    }

    @Test
    fun `alpha is left alone`() {
        assertEquals(0x80000000.toInt(), invert(0x80FFFFFF.toInt()))
    }

    /** Applies the matrix the way the platform does: 4x5, then clamped to a channel's range. */
    private fun invert(argb: Int): Int {
        val channels = floatArrayOf(
            ((argb shr 16) and 0xFF).toFloat(),
            ((argb shr 8) and 0xFF).toFloat(),
            (argb and 0xFF).toFloat(),
            ((argb shr 24) and 0xFF).toFloat(),
        )
        val out = IntArray(4)
        for (row in 0..3) {
            var sum = PdfNightMode.InvertMatrix[row * 5 + 4]
            for (column in 0..3) {
                sum += PdfNightMode.InvertMatrix[row * 5 + column] * channels[column]
            }
            out[row] = sum.toInt().coerceIn(0, 255)
        }
        return (out[3] shl 24) or (out[0] shl 16) or (out[1] shl 8) or out[2]
    }
}
