package com.papyrus.app.viewer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** The longest side a drawing is rasterised to, and the shortest it is brought up to. */
internal const val SVG_RASTER_MAX_EDGE = 1600
internal const val SVG_RASTER_MIN_EDGE = 64

private const val OPAQUE = 0xFF000000.toInt()
private const val PNG_QUALITY = 100

/**
 * The one place an SVG meets the platform: a [SvgScene] drawn with a [Path] and a [Paint] per shape into a
 * transparent bitmap, and encoded as a PNG. Nothing is parsed here and nothing is kept: the bitmap lives only
 * as long as the encode, and any failure is a null, which the caller turns into one dropped picture.
 */
internal object EpubSvgRaster : SvgRasteriser {

    override fun rasterise(scene: SvgScene): ByteArray? {
        if (scene.shapes.isEmpty() || scene.width <= 0f || scene.height <= 0f) return null
        return try {
            draw(scene)
        } catch (_: RuntimeException) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun draw(scene: SvgScene): ByteArray? {
        // The long edge is the drawing's own size kept between the two bounds; both sides are then at most
        // 1600 pixels, which is the cap on the bitmap's total size too.
        val longEdge = maxOf(scene.width, scene.height)
        val scale = longEdge.coerceIn(SVG_RASTER_MIN_EDGE.toFloat(), SVG_RASTER_MAX_EDGE.toFloat()) / longEdge
        val pixelsWide = (scene.width * scale).roundToInt().coerceAtLeast(1)
        val pixelsHigh = (scene.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(pixelsWide, pixelsHigh, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.scale(scale, scale)
            for (shape in scene.shapes) drawShape(canvas, shape)
            val out = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)) return null
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun drawShape(canvas: Canvas, shape: SvgShape) {
        val path = Path()
        for (op in shape.ops) {
            when (op) {
                is PathOp.MoveTo -> path.moveTo(op.x, op.y)
                is PathOp.LineTo -> path.lineTo(op.x, op.y)
                PathOp.Close -> path.close()
            }
        }
        shape.fill?.let { rgb ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.style = Paint.Style.FILL
            paint.color = OPAQUE or rgb
            canvas.drawPath(path, paint)
        }
        shape.stroke?.let { rgb ->
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = shape.strokeWidth
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = OPAQUE or rgb
            canvas.drawPath(path, paint)
        }
    }
}
