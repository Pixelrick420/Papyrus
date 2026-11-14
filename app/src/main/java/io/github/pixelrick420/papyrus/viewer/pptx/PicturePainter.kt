package io.github.pixelrick420.papyrus.viewer.pptx

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws one picture into [Canvas], with its frame transform, crop, fill, flips and mask. The caller supplies the
 * pixels-per-EMU scale; the picture is drawn in its own local space, with `0,0` at its frame's top-left.
 */
internal class PicturePainter(
    presets: PresetGeometry,
    private val images: PictureImages,
) {

    private val geometry = ShapeGeometry(presets)

    /** Returns false when nothing could be drawn, which the caller may treat as an incomplete slide. */
    fun draw(canvas: Canvas, picture: ScenePicture, pixelsPerEmu: Float): Boolean {
        val frame = picture.frame ?: return false
        if (frame.width <= 0.0 || frame.height <= 0.0 || pixelsPerEmu <= 0f) return false
        val source = picture.source
        val target = targetWidth(source, frame, pixelsPerEmu)
        val content = images.content(source, target)
        if (content == PictureContent.Missing) return false

        canvas.save()
        try {
            canvas.concat(matrixOf(picture, frame, pixelsPerEmu))
            canvas.clipRect(0f, 0f, frame.width.toFloat(), frame.height.toFloat())
            geometry.outline(picture)?.let { canvas.clipPath(pathOf(it)) }
            when (content) {
                is PictureContent.Image -> drawImage(canvas, content.image, source, frame)
                is PictureContent.Placeholder -> drawPlaceholder(canvas, frame, content.label)
                PictureContent.Missing -> Unit
            }
            return true
        } finally {
            canvas.restore()
        }
    }

    private fun matrixOf(picture: ScenePicture, frame: SceneRect, pixelsPerEmu: Float): Matrix {
        val transform = picture.transform
        val affine = Affine.forShape(
            frame = frame,
            rotation = number(transform?.attribute("rot")),
            flipH = isOn(transform?.attribute("flipH")),
            flipV = isOn(transform?.attribute("flipV")),
        ).then(Affine.scale(pixelsPerEmu.toDouble(), pixelsPerEmu.toDouble()))
        return affine.toMatrix()
    }

    private fun drawImage(
        canvas: Canvas,
        image: PictureImage,
        source: PictureSource?,
        frame: SceneRect,
    ) {
        val crop = source?.crop ?: PictureCrop.NONE
        if (!crop.isVisible) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            alpha = ((source?.alpha ?: 1.0) * OPAQUE).roundToInt().coerceIn(0, OPAQUE)
            isDither = true
        }
        when (val fill = source?.fill ?: PictureFill.Stretch()) {
            is PictureFill.Stretch -> drawCropped(canvas, image, crop, stretchRect(frame, fill.insets), paint)
            is PictureFill.Tile -> drawTiles(canvas, image, crop, fill.tile, frame, paint)
        }
    }

    private fun drawTiles(
        canvas: Canvas,
        image: PictureImage,
        crop: PictureCrop,
        tile: PictureTile,
        frame: SceneRect,
        paint: Paint,
    ) {
        val size = tileSize(image.bounds, tile)
        if (size.width <= 0.0 || size.height <= 0.0) return
        val origin = tileOrigin(frame, size, tile.alignment)
        val startX = firstTile(origin.x + tile.offsetXEmu, size.width)
        val startY = firstTile(origin.y + tile.offsetYEmu, size.height)
        val columns = visibleTiles(startX, size.width, frame.width)
        val rows = visibleTiles(startY, size.height, frame.height)
        if (columns <= 0 || rows <= 0) return
        if (columns.toLong() * rows > MAX_TILES) return
        var y = startY
        for (row in 0 until rows) {
            var x = startX
            for (column in 0 until columns) {
                drawTile(canvas, image, crop, SceneRect(x, y, size.width, size.height), tile.flip, paint)
                x += size.width
            }
            y += size.height
        }
    }

    private fun drawTile(
        canvas: Canvas,
        image: PictureImage,
        crop: PictureCrop,
        tile: SceneRect,
        flip: PictureTileFlip,
        paint: Paint,
    ) {
        canvas.save()
        try {
            if (flip.horizontal || flip.vertical) {
                val centreX = (tile.x + tile.width / 2.0).toFloat()
                val centreY = (tile.y + tile.height / 2.0).toFloat()
                canvas.scale(
                    if (flip.horizontal) -1f else 1f,
                    if (flip.vertical) -1f else 1f,
                    centreX,
                    centreY,
                )
            }
            drawCropped(canvas, image, crop, tile, paint)
        } finally {
            canvas.restore()
        }
    }

    private fun drawCropped(canvas: Canvas, image: PictureImage, crop: PictureCrop, destination: SceneRect, paint: Paint) {
        if (destination.width <= 0.0 || destination.height <= 0.0) return
        val content = contentRect(destination, crop)
        if (!content.isFinite() || content.width <= 0.0 || content.height <= 0.0) return
        canvas.save()
        try {
            canvas.clipRect(
                destination.x.toFloat(),
                destination.y.toFloat(),
                destination.right.toFloat(),
                destination.bottom.toFloat(),
            )
            canvas.drawBitmap(image.bitmap, null, content.toRectF(), paint)
        } finally {
            canvas.restore()
        }
    }

    private fun drawPlaceholder(canvas: Canvas, frame: SceneRect, label: String) {
        val width = frame.width.toFloat()
        val height = frame.height.toFloat()
        if (width <= 0f || height <= 0f) return
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PLACEHOLDER_FILL
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, width, height, fill)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PLACEHOLDER_BORDER
            style = Paint.Style.STROKE
            strokeWidth = (min(width, height) * BORDER_FRACTION).coerceAtLeast(1f)
        }
        canvas.drawRect(0f, 0f, width, height, border)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = PLACEHOLDER_TEXT
            textAlign = Paint.Align.CENTER
            textSize = (min(width, height) * TEXT_FRACTION).coerceAtLeast(1f)
        }
        val available = width * TEXT_WIDTH_FRACTION
        val measured = text.measureText(label)
        if (measured > available && measured > 0f) text.textSize *= available / measured
        val metrics = text.fontMetrics
        canvas.drawText(label, width / 2f, height / 2f - (metrics.ascent + metrics.descent) / 2f, text)
    }

    private fun targetWidth(source: PictureSource?, frame: SceneRect, pixelsPerEmu: Float): Int {
        val crop = source?.crop ?: PictureCrop.NONE
        val width = when (val fill = source?.fill) {
            is PictureFill.Tile -> images.bounds(source)?.let { tileSize(it, fill.tile).width } ?: frame.width
            else -> frame.width
        }
        val contentWidth = width / crop.width.coerceAtLeast(MIN_CROP_FRACTION)
        return (contentWidth * pixelsPerEmu).roundToInt().coerceIn(1, PptxLimits().maxRenderEdgePx)
    }

    private fun pathOf(outline: PresetOutline): Path {
        val path = Path()
        for (contour in outline.paths) {
            for (segment in contour.segments) {
                when (segment) {
                    is PresetSegment.MoveTo -> path.moveTo(segment.x.toFloat(), segment.y.toFloat())
                    is PresetSegment.LineTo -> path.lineTo(segment.x.toFloat(), segment.y.toFloat())
                    is PresetSegment.QuadTo -> path.quadTo(
                        segment.controlX.toFloat(),
                        segment.controlY.toFloat(),
                        segment.x.toFloat(),
                        segment.y.toFloat(),
                    )
                    is PresetSegment.CubicTo -> path.cubicTo(
                        segment.control1X.toFloat(),
                        segment.control1Y.toFloat(),
                        segment.control2X.toFloat(),
                        segment.control2Y.toFloat(),
                        segment.x.toFloat(),
                        segment.y.toFloat(),
                    )
                    PresetSegment.Close -> path.close()
                }
            }
        }
        return path
    }

    private fun SceneRect.toRectF(): RectF = RectF(x.toFloat(), y.toFloat(), right.toFloat(), bottom.toFloat())

    private fun Affine.toMatrix(): Matrix = Matrix().apply {
        setValues(
            floatArrayOf(
                a.toFloat(), c.toFloat(), e.toFloat(),
                b.toFloat(), d.toFloat(), f.toFloat(),
                0f, 0f, 1f,
            ),
        )
    }

    private fun SceneRect.isFinite(): Boolean = x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite()

    private fun number(value: String?): Double = value?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0

    private fun isOn(value: String?): Boolean = value == "1" || value.equals("true", ignoreCase = true)

    private companion object {
        const val OPAQUE = 255
        const val MAX_TILES = 4_096L
        const val MIN_CROP_FRACTION = 0.01
        const val BORDER_FRACTION = 0.004f
        const val TEXT_FRACTION = 0.14f
        const val TEXT_WIDTH_FRACTION = 0.8f
        const val PLACEHOLDER_FILL = 0xFFEFEFEF.toInt()
        const val PLACEHOLDER_BORDER = 0xFFB0B0B0.toInt()
        const val PLACEHOLDER_TEXT = 0xFF616161.toInt()
    }
}
