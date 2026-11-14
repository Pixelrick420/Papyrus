package io.github.pixelrick420.papyrus.viewer.pptx.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF as AndroidRectF
import android.graphics.Shader
import android.graphics.Typeface
import io.github.pixelrick420.papyrus.viewer.pptx.Affine
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.StrokeCap
import io.github.pixelrick420.papyrus.viewer.pptx.StrokeJoin
import kotlin.math.roundToInt

/**
 * Draws a display list into [canvas]. In production the canvas is backed by a bitmap, so Skia's CPU
 * rasteriser produces the same gradients and blur masks on every device.
 */
internal class AndroidCanvasBackend(
    private val canvas: Canvas,
    private val imageOf: (ImageRef) -> Bitmap? = { null },
) : DrawTarget {

    private var shadow: ShadowSpec? = null

    private var depth = 0

    override fun fillPath(path: PathData, paint: PaintSpec) {
        val fill = paintOf(paint)
        fill.style = Paint.Style.FILL
        canvas.drawPath(pathOf(path), fill)
    }

    override fun strokePath(path: PathData, stroke: StrokeSpec) {
        val paint = paintOf(stroke.paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke.width
        paint.strokeCap = stroke.cap.toAndroid()
        paint.strokeJoin = stroke.join.toAndroid()
        paint.strokeMiter = stroke.miterLimit
        paint.pathEffect = stroke.dash.takeIf { it.size >= 2 }?.let { DashPathEffect(dashArray(it), 0f) }
        canvas.drawPath(pathOf(path), paint)
    }

    override fun drawImage(image: ImageRef, src: RectF, dst: RectF, effects: ImageEffects) {
        val bitmap = imageOf(image) ?: return
        val paint = paintOf(PaintSpec.Solid(Rgba.WHITE))
        paint.alpha = (effects.alpha.coerceIn(0f, 1f) * OPAQUE).roundToInt()
        val saved = effects.clip?.let { canvas.save(); canvas.clipPath(pathOf(it)); true } ?: false
        try {
            val source = src.takeIf { it.width > 0f && it.height > 0f }?.toAndroidRect()
            canvas.drawBitmap(bitmap, source, dst.toAndroid(), paint)
        } finally {
            if (saved) canvas.restore()
        }
    }

    override fun drawGlyphRun(run: GlyphRun) {
        val paint = paintOf(run.paint)
        paint.textSize = run.size
        paint.typeface = Typeface.create(run.family, run.typefaceStyle())
        canvas.drawText(run.text, run.x, run.y, paint)
    }

    override fun save() {
        canvas.save()
        depth++
    }

    override fun restore() {
        if (depth == 0) return
        canvas.restore()
        depth--
    }

    override fun concat(m: Affine) {
        val matrix = Matrix().apply {
            setValues(
                floatArrayOf(
                    m.a.toFloat(), m.c.toFloat(), m.e.toFloat(),
                    m.b.toFloat(), m.d.toFloat(), m.f.toFloat(),
                    0f, 0f, 1f,
                ),
            )
        }
        canvas.concat(matrix)
    }

    override fun clip(path: PathData) {
        canvas.clipPath(pathOf(path))
    }

    override fun shadow(shadow: ShadowSpec?) {
        this.shadow = shadow
    }

    private fun paintOf(spec: PaintSpec): Paint {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        when (spec) {
            is PaintSpec.Solid -> paint.color = spec.color.argb
            is PaintSpec.Linear -> paint.applyGradient(spec.stops) { colors, offsets ->
                LinearGradient(
                    spec.start.x, spec.start.y, spec.end.x, spec.end.y, colors, offsets, Shader.TileMode.CLAMP,
                )
            }
            is PaintSpec.Radial -> paint.applyGradient(spec.stops) { colors, offsets ->
                RadialGradient(
                    spec.center.x, spec.center.y, spec.radius.coerceAtLeast(MIN_RADIUS), colors, offsets,
                    Shader.TileMode.CLAMP,
                )
            }
        }
        shadow?.let { paint.setShadowLayer(it.blur, it.dx, it.dy, it.color.argb) }
        return paint
    }

    private fun pathOf(data: PathData): Path {
        val path = Path()
        path.fillType =
            if (data.fill == PathFill.EVEN_ODD) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        for (segment in data.segments) {
            when (segment) {
                is PathSegment.MoveTo -> path.moveTo(segment.x, segment.y)
                is PathSegment.LineTo -> path.lineTo(segment.x, segment.y)
                is PathSegment.QuadTo -> path.quadTo(segment.controlX, segment.controlY, segment.x, segment.y)
                is PathSegment.CubicTo -> path.cubicTo(
                    segment.control1X, segment.control1Y, segment.control2X, segment.control2Y,
                    segment.x, segment.y,
                )
                PathSegment.Close -> path.close()
            }
        }
        return path
    }

    private fun Paint.applyGradient(stops: List<ColorStop>, shaderOf: (IntArray, FloatArray) -> Shader) {
        if (stops.size < 2) {
            color = stops.firstOrNull()?.color?.argb ?: 0
            return
        }
        val ordered = stops.sortedBy { it.offset }
        shader = shaderOf(
            ordered.map { it.color.argb }.toIntArray(),
            ordered.map { it.offset.coerceIn(0f, 1f) }.toFloatArray(),
        )
    }

    private fun dashArray(dash: List<Float>): FloatArray {
        val values = dash.map { it.coerceAtLeast(MIN_DASH) }
        return if (values.size % 2 == 0) values.toFloatArray() else (values + values.first()).toFloatArray()
    }

    private fun RectF.toAndroid(): AndroidRectF = AndroidRectF(left, top, right, bottom)

    private fun RectF.toAndroidRect(): Rect =
        Rect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())

    private fun GlyphRun.typefaceStyle(): Int = when {
        bold && italic -> Typeface.BOLD_ITALIC
        bold -> Typeface.BOLD
        italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }

    private fun StrokeCap.toAndroid(): Paint.Cap = when (this) {
        StrokeCap.FLAT -> Paint.Cap.BUTT
        StrokeCap.ROUND -> Paint.Cap.ROUND
        StrokeCap.SQUARE -> Paint.Cap.SQUARE
    }

    private fun StrokeJoin.toAndroid(): Paint.Join = when (this) {
        StrokeJoin.ROUND -> Paint.Join.ROUND
        StrokeJoin.BEVEL -> Paint.Join.BEVEL
        StrokeJoin.MITER -> Paint.Join.MITER
    }

    private companion object {
        const val OPAQUE = 255
        const val MIN_RADIUS = 0.01f
        const val MIN_DASH = 0.01f
    }
}

/** Renders a display list into an `ARGB_8888` bitmap at density NONE, so its pixels do not scale. */
internal object SoftwareCanvas {

    fun render(
        list: DisplayList,
        widthPx: Int,
        heightPx: Int,
        scale: Float = 1f,
        imageOf: (ImageRef) -> Bitmap? = { null },
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bitmap.density = Bitmap.DENSITY_NONE
        val canvas = Canvas(bitmap)
        if (scale != 1f) canvas.scale(scale, scale)
        AndroidCanvasBackend(canvas, imageOf).also { list.play(it) }
        return bitmap
    }
}
