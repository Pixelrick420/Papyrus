package io.github.pixelrick420.papyrus.viewer.pptx.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Region
import android.graphics.RectF as AndroidRectF
import android.graphics.Shader
import android.graphics.Typeface
import io.github.pixelrick420.papyrus.viewer.pptx.Affine
import io.github.pixelrick420.papyrus.viewer.pptx.EMU_PER_PIXEL
import io.github.pixelrick420.papyrus.viewer.pptx.PatternTile
import io.github.pixelrick420.papyrus.viewer.pptx.PictureEffects
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.StrokeCap
import io.github.pixelrick420.papyrus.viewer.pptx.StrokeJoin
import io.github.pixelrick420.papyrus.viewer.pptx.text.FontCatalog
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws a display list into [canvas]. In production the canvas is backed by a bitmap, so Skia's CPU
 * rasteriser produces the same gradients and blur masks on every device.
 */
internal class AndroidCanvasBackend(
    private val canvas: Canvas,
    private val imageOf: (ImageRef) -> Bitmap? = { null },
    private val fonts: FontCatalog? = null,
    initialScale: Float = 1f,
) : DrawTarget {

    private var shadow: ShadowSpec? = null

    private var depth = 0

    private val transforms = mutableListOf<Affine>()

    private var transform = Affine.scale(initialScale.toDouble(), initialScale.toDouble())

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
        val drawn = if (effects.recolor.hasPixelWork) recolour(bitmap, effects.recolor) else bitmap
        val paint = paintOf(PaintSpec.Solid(Rgba.WHITE))
        paint.alpha = (effects.alpha.coerceIn(0f, 1f) * OPAQUE).roundToInt()
        effects.recolor.matrix?.let { paint.colorFilter = ColorMatrixColorFilter(it.toFloatArray()) }
        val saved = effects.clip?.let { canvas.save(); canvas.clipPath(pathOf(it)); true } ?: false
        try {
            val source = src.takeIf { it.width > 0f && it.height > 0f }?.let { r ->
                val sx = if (image.width > 0) drawn.width.toFloat() / image.width else 1f
                val sy = if (image.height > 0) drawn.height.toFloat() / image.height else 1f
                Rect(
                    (r.left * sx).roundToInt(),
                    (r.top * sy).roundToInt(),
                    (r.right * sx).roundToInt(),
                    (r.bottom * sy).roundToInt(),
                )
            }
            canvas.drawBitmap(drawn, source, dst.toAndroid(), paint)
        } finally {
            if (saved) canvas.restore()
        }
    }

    private fun recolour(bitmap: Bitmap, effects: PictureEffects): Bitmap {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (index in pixels.indices) {
            val packed = pixels[index]
            val color = Rgba((packed shr 16) and 0xFF, (packed shr 8) and 0xFF, packed and 0xFF, (packed shr 24) and 0xFF)
            pixels[index] = effects.applyTo(color).argb
        }
        return Bitmap.createBitmap(pixels, bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    }

    override fun drawGlyphRun(run: GlyphRun) {
        if (run.text.isEmpty()) return
        // Skia drops glyphs built at slide-sized (EMU) text, so the fill is painted in a space whose
        // scale is 1: the run's coordinates and pen size are converted to device units first.
        val sx = hypot(transform.a, transform.b).toFloat()
        val sy = hypot(transform.c, transform.d).toFloat()
        if (sx > 0f && sy > 0f) {
            val fill = textPaint(run.paint, run).apply {
                textSize = run.size * sy
                textScaleX = sx / sy
                shader?.setLocalMatrix(Matrix().apply { setScale(sx, sy) })
            }
            run.shadow?.let { fill.setShadowLayer(it.blur * sy, it.dx * sx, it.dy * sy, it.color.argb) }
            canvas.save()
            canvas.scale(1f / sx, 1f / sy)
            canvas.drawText(run.text, run.x * sx, run.y * sy, fill)
            canvas.restore()
        }
        val paint = textPaint(run.paint, run)
        run.outline?.let { outline ->
            val path = Path()
            paint.getTextPath(run.text, 0, run.text.length, run.x, run.y, path)
            val stroke = textPaint(outline.paint, run)
            stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = outline.width
            stroke.strokeJoin = Paint.Join.ROUND
            canvas.drawPath(path, stroke)
        }
        run.rules.forEach { drawTextRule(it, run) }
    }

    private fun textPaint(spec: PaintSpec, run: GlyphRun): Paint = paintOf(spec).apply {
        textSize = run.size
        typeface = run.font?.let { fonts?.typeface(it) } ?: Typeface.create(run.family, run.typefaceStyle())
        letterSpacing = run.letterSpacing
        fontFeatureSettings = featureSettings(run)
    }

    private fun drawTextRule(rule: TextRule, run: GlyphRun) {
        val paint = paintOf(rule.paint ?: run.paint)
        val thickness = rule.thickness * if (rule.style.endsWith("Heavy") || rule.style == "heavy") {
            HEAVY_MULTIPLIER
        } else {
            1f
        }
        val style = rule.style
        when {
            style.startsWith("wavy") -> {
                val lines = if (style == "wavyDouble") listOf(-thickness, thickness) else listOf(0f)
                for (offset in lines) {
                    strokeRule(paint, wavyPath(rule, rule.y + offset, thickness), thickness, null)
                }
            }
            style.startsWith("dotted") -> strokeRule(
                paint,
                straightPath(rule),
                thickness,
                DashPathEffect(floatArrayOf(thickness, thickness), 0f),
            )
            style.startsWith("dashLong") -> strokeRule(
                paint,
                straightPath(rule),
                thickness,
                DashPathEffect(floatArrayOf(thickness * DASH_LONG, thickness * DASH_GAP), 0f),
            )
            style.startsWith("dash") -> strokeRule(
                paint,
                straightPath(rule),
                thickness,
                DashPathEffect(floatArrayOf(thickness * DASH, thickness * DASH_GAP), 0f),
            )
            else -> {
                val double = style == "dbl" || style == "dblStrike"
                val offsets = if (double) listOf(-thickness * DOUBLE_GAP, thickness * DOUBLE_GAP) else listOf(0f)
                val height = if (double) thickness * DOUBLE_THICKNESS else thickness
                paint.style = Paint.Style.FILL
                for (offset in offsets) {
                    canvas.drawRect(rule.left, rule.y + offset - height / 2f, rule.right, rule.y + offset + height / 2f, paint)
                }
            }
        }
    }

    private fun strokeRule(paint: Paint, path: Path, thickness: Float, effect: DashPathEffect?) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = thickness
        paint.pathEffect = effect
        canvas.drawPath(path, paint)
    }

    private fun straightPath(rule: TextRule): Path = Path().apply {
        moveTo(rule.left, rule.y)
        lineTo(rule.right, rule.y)
    }

    private fun wavyPath(rule: TextRule, y: Float, amplitude: Float): Path {
        val path = Path()
        val step = (amplitude * WAVE_STEPS / WAVE_PERIOD).coerceAtLeast(MIN_DASH)
        val turn = Math.PI * 2 / WAVE_STEPS
        path.moveTo(rule.left, y)
        var x = rule.left
        var phase = 0.0
        while (x < rule.right) {
            val next = (x + step).coerceAtMost(rule.right)
            phase += turn
            path.lineTo(next, y + (sin(phase) * amplitude).toFloat())
            x = next
        }
        return path
    }

    private fun featureSettings(run: GlyphRun): String =
        "\"kern\" ${if (run.kerning) 1 else 0}, " +
            "\"liga\" ${if (run.ligatures) 1 else 0}, \"clig\" 0"

    override fun save() {
        canvas.save()
        transforms.add(transform)
        depth++
    }

    override fun restore() {
        if (depth == 0) return
        canvas.restore()
        transform = transforms.removeAt(transforms.lastIndex)
        depth--
    }

    override fun concat(m: Affine) {
        canvas.concat(matrixOf(m))
        transform = m.then(transform)
    }

    private fun matrixOf(m: Affine): Matrix = Matrix().apply {
        setValues(
            floatArrayOf(
                m.a.toFloat(), m.c.toFloat(), m.e.toFloat(),
                m.b.toFloat(), m.d.toFloat(), m.f.toFloat(),
                0f, 0f, 1f,
            ),
        )
    }

    override fun clip(path: PathData) {
        canvas.clipPath(pathOf(path))
    }

    override fun shadow(shadow: ShadowSpec?) {
        this.shadow = shadow
    }

    @Suppress("DEPRECATION")
    override fun shapeShadow(path: PathData, shadow: ShapeShadowSpec) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = shadow.color.argb
            style = if (shadow.inner) Paint.Style.STROKE else Paint.Style.FILL
            if (shadow.inner) strokeWidth = shadow.blur.coerceAtLeast(MIN_RADIUS)
            maskFilter = BlurMaskFilter(shadow.blur.coerceAtLeast(MIN_RADIUS), BlurMaskFilter.Blur.NORMAL)
        }
        val androidPath = pathOf(path)
        val saved = canvas.save()
        if (shadow.inner) {
            canvas.clipPath(androidPath)
        } else {
            canvas.clipPath(androidPath, Region.Op.DIFFERENCE)
        }
        canvas.concat(matrixOf(shadow.transform))
        canvas.drawPath(androidPath, paint)
        canvas.restoreToCount(saved)
    }

    override fun glow(path: PathData, color: Rgba, radius: Float) {
        val blur = radius.coerceAtLeast(MIN_RADIUS)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.argb
            style = Paint.Style.STROKE
            strokeWidth = blur
            maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawPath(pathOf(path), paint)
    }

    override fun softEdge(path: PathData, paint: PaintSpec, radius: Float) =
        blurredFill(path, paint, radius)

    override fun blur(path: PathData, paint: PaintSpec, radius: Float) =
        blurredFill(path, paint, radius)

    private fun blurredFill(path: PathData, spec: PaintSpec, radius: Float) {
        val paint = paintOf(spec).apply {
            style = Paint.Style.FILL
            maskFilter = BlurMaskFilter(radius.coerceAtLeast(MIN_RADIUS), BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawPath(pathOf(path), paint)
    }

    override fun reflection(path: PathData, paint: PaintSpec, spec: ReflectionSpec) {
        val androidPath = pathOf(path)
        val bounds = AndroidRectF()
        androidPath.computeBounds(bounds, true)
        val width = bounds.right - bounds.left
        val height = bounds.bottom - bounds.top
        if (width <= 0f || height <= 0f) return
        val radians = Math.toRadians(spec.directionDegrees.toDouble())
        val dx = (cos(radians) * spec.distance).toFloat()
        val dy = (sin(radians) * spec.distance).toFloat()
        val blur = spec.blur.coerceAtLeast(0f)
        val reflected = AndroidRectF(
            bounds.left + dx - blur,
            bounds.bottom + dy - blur,
            bounds.right + dx + blur,
            bounds.bottom + dy + height + blur,
        )
        val layer = canvas.saveLayer(reflected, null)
        val saved = canvas.save()
        canvas.translate(bounds.left + dx, bounds.bottom + dy)
        canvas.scale(1f, -1f)
        canvas.translate(-bounds.left, -bounds.bottom)
        val reflectionPaint = paintOf(paint).apply {
            style = Paint.Style.FILL
            if (blur > 0f) maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawPath(androidPath, reflectionPaint)
        canvas.restoreToCount(saved)
        val top = reflected.top
        val span = reflected.bottom - top
        val start = top + span * spec.startPosition.coerceIn(0f, 1f)
        val end = (top + span * spec.endPosition.coerceIn(0f, 1f)).coerceAtLeast(start + MIN_RADIUS)
        val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, start, 0f, end,
                intArrayOf(alphaColor(spec.startAlpha), alphaColor(spec.endAlpha)),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP,
            )
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawRect(reflected, mask)
        canvas.restoreToCount(layer)
    }

    private fun alphaColor(alpha: Float): Int =
        Color.argb((alpha.coerceIn(0f, 1f) * OPAQUE).roundToInt(), OPAQUE, OPAQUE, OPAQUE)

    private fun paintOf(spec: PaintSpec): Paint {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        when (spec) {
            is PaintSpec.Solid -> paint.color = spec.color.argb
            is PaintSpec.Linear -> paint.applyGradient(spec.stops) { colors, offsets ->
                LinearGradient(
                    spec.start.x, spec.start.y, spec.end.x, spec.end.y, colors, offsets, shaderTileMode(spec.tileMode),
                )
            }
            is PaintSpec.Radial -> paint.applyGradient(spec.stops) { colors, offsets ->
                RadialGradient(
                    spec.center.x, spec.center.y, spec.radius.coerceAtLeast(MIN_RADIUS), colors, offsets,
                    shaderTileMode(spec.tileMode),
                )
            }
            is PaintSpec.Pattern -> {
                val shader = BitmapShader(patternBitmap(spec), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
                val tileScale = (EMU_PER_PIXEL * spec.scale.coerceAtLeast(MIN_TILE_SCALE)).toFloat()
                shader.setLocalMatrix(Matrix().apply {
                    setScale(tileScale, tileScale)
                    if (spec.rotationDegrees != 0f) postRotate(spec.rotationDegrees)
                })
                paint.shader = shader
            }
        }
        shadow?.let { paint.setShadowLayer(it.blur, it.dx, it.dy, it.color.argb) }
        return paint
    }

    private fun shaderTileMode(mode: GradientTileMode): Shader.TileMode = when (mode) {
        GradientTileMode.CLAMP -> Shader.TileMode.CLAMP
        GradientTileMode.REPEAT -> Shader.TileMode.REPEAT
        GradientTileMode.MIRROR -> Shader.TileMode.MIRROR
    }

    private fun patternBitmap(spec: PaintSpec.Pattern): Bitmap {
        val bitmap = Bitmap.createBitmap(PatternTile.SIZE, PatternTile.SIZE, Bitmap.Config.ARGB_8888)
        for (y in 0 until PatternTile.SIZE) {
            for (x in 0 until PatternTile.SIZE) {
                val color = if (spec.tile.pixel(x, y)) spec.foreground.argb else (spec.background?.argb ?: 0)
                bitmap.setPixel(x, y, color)
            }
        }
        return bitmap
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
        const val MIN_TILE_SCALE = 0.001f
        const val MIN_DASH = 0.01f
        const val HEAVY_MULTIPLIER = 2f
        const val DOUBLE_GAP = 0.75f
        const val DOUBLE_THICKNESS = 0.5f
        const val DASH = 4f
        const val DASH_LONG = 8f
        const val DASH_GAP = 3f
        const val WAVE_STEPS = 16
        const val WAVE_PERIOD = 4f
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
        fonts: FontCatalog? = null,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(widthPx.coerceAtLeast(1), heightPx.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        bitmap.density = Bitmap.DENSITY_NONE
        val canvas = Canvas(bitmap)
        if (scale != 1f) canvas.scale(scale, scale)
        AndroidCanvasBackend(canvas, imageOf, fonts, initialScale = scale).also { list.play(it) }
        return bitmap
    }

    fun renderRegion(
        list: DisplayList,
        region: RenderRegion,
        imageOf: (ImageRef) -> Bitmap? = { null },
        fonts: FontCatalog? = null,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(region.widthPx, region.heightPx, Bitmap.Config.ARGB_8888)
        bitmap.density = Bitmap.DENSITY_NONE
        val canvas = Canvas(bitmap)
        AndroidCanvasBackend(canvas, imageOf, fonts).also { list.playRegion(it, region) }
        return bitmap
    }
}
