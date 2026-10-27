package com.papyrus.app.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The real rasteriser, on a device: Bitmap and Canvas are only real there. */
@RunWith(AndroidJUnit4::class)
class EpubSvgRasterTest {

    private fun square(x: Float, y: Float, size: Float) = listOf(
        PathOp.MoveTo(x, y),
        PathOp.LineTo(x + size, y),
        PathOp.LineTo(x + size, y + size),
        PathOp.LineTo(x, y + size),
        PathOp.Close,
    )

    private fun decode(bytes: ByteArray): Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!

    private fun hasInk(bitmap: Bitmap): Boolean {
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) if (Color.alpha(bitmap.getPixel(x, y)) > 0) return true
        return false
    }

    @Test
    fun a_two_shape_scene_becomes_a_png_the_platform_decodes() {
        val scene = SvgScene(
            100f,
            50f,
            listOf(
                SvgShape(square(5f, 5f, 20f), closed = true, fill = 0xFF0000, stroke = null, strokeWidth = 1f),
                SvgShape(square(50f, 10f, 30f), closed = true, fill = null, stroke = 0x0000FF, strokeWidth = 2f),
            ),
            tintable = false,
        )

        val png = EpubSvgRaster.rasterise(scene)
        assertNotNull(png)
        val bitmap = decode(png!!)

        assertTrue(hasInk(bitmap))
        assertEquals(2f, bitmap.width.toFloat() / bitmap.height, 0.05f)
    }

    @Test
    fun the_long_edge_is_capped() {
        val scene = SvgScene(
            10_000f,
            5_000f,
            listOf(SvgShape(square(0f, 0f, 4_000f), closed = true, fill = 0x000000, stroke = null, strokeWidth = 1f)),
            tintable = true,
        )

        val bitmap = decode(EpubSvgRaster.rasterise(scene)!!)

        assertTrue(maxOf(bitmap.width, bitmap.height) <= SVG_RASTER_MAX_EDGE)
    }

    @Test
    fun a_tiny_scene_is_brought_up_to_a_visible_size() {
        val scene = SvgScene(
            4f,
            4f,
            listOf(SvgShape(square(0f, 0f, 4f), closed = true, fill = 0x000000, stroke = null, strokeWidth = 1f)),
            tintable = true,
        )

        val bitmap = decode(EpubSvgRaster.rasterise(scene)!!)

        assertTrue(maxOf(bitmap.width, bitmap.height) >= SVG_RASTER_MIN_EDGE)
    }

    @Test
    fun a_black_only_scene_and_a_coloured_one_both_draw_pixels() {
        fun scene(rgb: Int, tintable: Boolean) = SvgScene(
            10f,
            10f,
            listOf(SvgShape(square(1f, 1f, 8f), closed = true, fill = rgb, stroke = null, strokeWidth = 1f)),
            tintable,
        )

        assertTrue(hasInk(decode(EpubSvgRaster.rasterise(scene(0x000000, tintable = true))!!)))
        assertTrue(hasInk(decode(EpubSvgRaster.rasterise(scene(0x33AA55, tintable = false))!!)))
    }

    @Test
    fun a_scene_with_no_shapes_is_null_not_an_empty_bitmap() {
        assertNull(EpubSvgRaster.rasterise(SvgScene(10f, 10f, emptyList(), tintable = true)))
    }

    @Test
    fun a_scene_with_no_size_is_null() {
        val shape = SvgShape(square(0f, 0f, 1f), closed = true, fill = 0, stroke = null, strokeWidth = 1f)
        assertNull(EpubSvgRaster.rasterise(SvgScene(0f, 10f, listOf(shape), tintable = true)))
    }
}
