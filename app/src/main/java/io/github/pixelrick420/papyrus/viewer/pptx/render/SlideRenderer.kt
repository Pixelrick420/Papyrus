package io.github.pixelrick420.papyrus.viewer.pptx.render

import io.github.pixelrick420.papyrus.viewer.pptx.Affine
import io.github.pixelrick420.papyrus.viewer.pptx.ArrowType
import io.github.pixelrick420.papyrus.viewer.pptx.ImageBounds
import io.github.pixelrick420.papyrus.viewer.pptx.PictureCrop
import io.github.pixelrick420.papyrus.viewer.pptx.PictureFill
import io.github.pixelrick420.papyrus.viewer.pptx.PictureInsets
import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import io.github.pixelrick420.papyrus.viewer.pptx.PresetFillMode
import io.github.pixelrick420.papyrus.viewer.pptx.PresetGeometry
import io.github.pixelrick420.papyrus.viewer.pptx.PresetOutline
import io.github.pixelrick420.papyrus.viewer.pptx.PresetOutlinePath
import io.github.pixelrick420.papyrus.viewer.pptx.PresetSegment
import io.github.pixelrick420.papyrus.viewer.pptx.Scene
import io.github.pixelrick420.papyrus.viewer.pptx.SceneConnector
import io.github.pixelrick420.papyrus.viewer.pptx.SceneFormat
import io.github.pixelrick420.papyrus.viewer.pptx.SceneGraphicFrame
import io.github.pixelrick420.papyrus.viewer.pptx.SceneGroup
import io.github.pixelrick420.papyrus.viewer.pptx.SceneLine
import io.github.pixelrick420.papyrus.viewer.pptx.SceneNode
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePicture
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePoint
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePreset
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import io.github.pixelrick420.papyrus.viewer.pptx.SceneShape
import io.github.pixelrick420.papyrus.viewer.pptx.ShapeGeometry
import io.github.pixelrick420.papyrus.viewer.pptx.StrokeCompound
import io.github.pixelrick420.papyrus.viewer.pptx.Strokes
import io.github.pixelrick420.papyrus.viewer.pptx.XNode
import io.github.pixelrick420.papyrus.viewer.pptx.chart.ChartDrawing
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** A picture resolved to its raster file and source size, which is all a display list needs to name it. */
internal class SlideImage(val file: File, val bounds: ImageBounds) {

    val key: String get() = file.path
}

/** Resolves a picture to drawable pixels; a picture this returns null for is dropped whole. */
internal fun interface SlidePictureResolver {

    fun imageOf(picture: ScenePicture): SlideImage?

    companion object {
        val NONE = SlidePictureResolver { null }
    }
}

/**
 * Walks one resolved [Scene] into a width-independent [DisplayList] in EMU. A node that cannot be drawn
 * faithfully is dropped whole, never approximated.
 */
internal class SlideRenderer(
    presets: PresetGeometry,
    private val limits: PptxLimits = PptxLimits(),
    private val nowNanos: () -> Long = System::nanoTime,
    private val text: SlideTextPainter? = null,
) {

    private val geometry = ShapeGeometry(presets)

    /** Null when the slide could not be finished: its time budget ran out, or the caller cancelled it. */
    suspend fun render(
        scene: Scene,
        pictures: SlidePictureResolver = SlidePictureResolver.NONE,
    ): DisplayList? {
        val ops = mutableListOf<DrawOp>()
        background(scene, ops)
        val deadline = nowNanos() + limits.maxSlideRenderMillis * NANOS_PER_MILLI
        if (!walk(scene, scene.nodes, ops, pictures, deadline)) return null
        return DisplayList(ops)
    }

    private suspend fun walk(
        scene: Scene,
        nodes: List<SceneNode>,
        out: MutableList<DrawOp>,
        pictures: SlidePictureResolver,
        deadline: Long,
    ): Boolean {
        for (node in nodes) {
            currentCoroutineContext().ensureActive()
            if (nowNanos() >= deadline) return false
            if (!drawNode(scene, node, out, pictures, deadline)) return false
        }
        return true
    }

    private suspend fun drawNode(
        scene: Scene,
        node: SceneNode,
        out: MutableList<DrawOp>,
        pictures: SlidePictureResolver,
        deadline: Long,
    ): Boolean {
        return when (node) {
            is SceneShape -> {
                shape(node, out)
                text?.paintShape(scene, node, out)
                true
            }
            is SceneConnector -> {
                connector(node, out)
                true
            }
            is ScenePicture -> {
                picture(node, out, pictures)
                true
            }
            is SceneGraphicFrame -> {
                graphicFrame(node, out, pictures)
                text?.paintTable(node, out)
                node.chart?.let { text?.paintChart(node, it, out) }
                val art = node.smartArt
                if (art != null) {
                    if (!walk(scene, art.nodes, out, pictures, deadline)) return false
                    text?.paintSmartArt(art, out)
                }
                true
            }
            is SceneGroup -> group(scene, node, out, pictures, deadline)
        }
    }

    private fun background(scene: Scene, out: MutableList<DrawOp>) {
        val box = SceneRect(0.0, 0.0, scene.size.width, scene.size.height)
        val paint = paintOf(scene.background, box) ?: return
        out += DrawOp.FillPath(
            PathData.rect(RectF(0f, 0f, scene.size.width.toFloat(), scene.size.height.toFloat())),
            paint,
        )
    }

    private suspend fun group(
        scene: Scene,
        group: SceneGroup,
        out: MutableList<DrawOp>,
        pictures: SlidePictureResolver,
        deadline: Long,
    ): Boolean {
        val frame = group.frame?.takeIf(::isDrawable) ?: return true
        val children = mutableListOf<DrawOp>()
        val complete = walk(scene, group.children, children, pictures, deadline)
        if (children.isNotEmpty()) {
            out += DrawOp.Save
            out += DrawOp.Concat(
                Affine.forShape(frame, group.childFrame, group.rotation, group.flipH, group.flipV),
            )
            out += children
            out += DrawOp.Restore
        }
        return complete
    }

    private fun shape(shape: SceneShape, out: MutableList<DrawOp>) {
        val frame = shape.frame?.takeIf(::isDrawable) ?: return
        val format = shape.format ?: return
        if (format.isUnsupported()) return
        val outline = geometry.outline(shape) ?: return
        val (flipH, flipV) = flipsOf(shape.preset)
        outline(outline, format, Affine.forShape(frame, rotation = rotationOf(shape.transform), flipH = flipH, flipV = flipV), out)
    }

    private fun connector(connector: SceneConnector, out: MutableList<DrawOp>) {
        val frame = connector.frame?.takeIf(::isDrawable) ?: return
        val format = connector.format ?: return
        if (format.isUnsupported() || format.line.hasArrows()) return
        val outline = geometry.outline(connector) ?: return
        val (flipH, flipV) = flipsOf(connector.preset)
        outline(
            outline,
            format,
            Affine.forShape(frame, rotation = rotationOf(connector.transform), flipH = flipH, flipV = flipV),
            out,
        )
    }

    private fun picture(picture: ScenePicture, out: MutableList<DrawOp>, pictures: SlidePictureResolver) {
        val frame = picture.frame?.takeIf { it.width > 0.0 && it.height > 0.0 } ?: return
        if (picture.format.isUnsupported()) return
        val source = picture.source ?: return
        val fill = source.fill
        if (fill !is PictureFill.Stretch || fill.insets != PictureInsets.NONE) return
        val crop = source.crop
        if (!crop.isVisible || crop.isNegative()) return
        val raster = pictures.imageOf(picture) ?: return
        val widthPx = raster.bounds.width.toDouble()
        val heightPx = raster.bounds.height.toDouble()
        out += DrawOp.Save
        out += DrawOp.Concat(
            Affine.forShape(
                frame,
                rotation = rotationOf(picture.transform),
                flipH = isOn(picture.transform, "flipH"),
                flipV = isOn(picture.transform, "flipV"),
            ),
        )
        out += DrawOp.DrawImage(
            image = ImageRef(raster.key, raster.bounds.width, raster.bounds.height),
            src = RectF(
                (crop.left * widthPx).toFloat(),
                (crop.top * heightPx).toFloat(),
                ((1.0 - crop.right) * widthPx).toFloat(),
                ((1.0 - crop.bottom) * heightPx).toFloat(),
            ),
            dst = RectF(0f, 0f, frame.width.toFloat(), frame.height.toFloat()),
            effects = ImageEffects(alpha = source.alpha.toFloat(), clip = maskOf(picture)),
        )
        out += DrawOp.Restore
    }

    private fun graphicFrame(frame: SceneGraphicFrame, out: MutableList<DrawOp>, pictures: SlidePictureResolver) {
        frame.fallbackPicture?.let { picture(it, out, pictures) }
        frame.chart?.let { chart(it, frame.frame, out) }
        val table = frame.table ?: return
        val layout = table.layout ?: return
        val origin = frame.frame ?: return
        out += DrawOp.Save
        out += DrawOp.Concat(Affine.translate(origin.x, origin.y))
        val rows = table.rows
        val cols = table.grid.columnWidths.size
        for (r in rows.indices) {
            val row = rows[r]
            for (c in 0 until minOf(row.cells.size, cols)) {
                val cell = row.cells[c]
                if (cell.horizontalMerge || cell.verticalMerge) continue
                val rect = layout.cellFrames[r to c] ?: continue
                val fill = cell.properties.fill
                val paint = fill?.let { paintOf(it, rect) }
                if (paint != null) {
                    out += DrawOp.FillPath(
                        PathData.rect(RectF(rect.x.toFloat(), rect.y.toFloat(), (rect.x + rect.width).toFloat(), (rect.y + rect.height).toFloat())),
                        paint,
                    )
                }
            }
        }
        for (border in layout.resolvedBorders) {
            val paint = border.border.paint?.let { paintOf(it, origin) } ?: continue
            val width = border.border.widthEmu.toFloat()
            if (width <= 0f) continue
            out += DrawOp.StrokePath(
                PathData(
                    listOf(
                        PathSegment.MoveTo(border.x1.toFloat(), border.y1.toFloat()),
                        PathSegment.LineTo(border.x2.toFloat(), border.y2.toFloat()),
                    ),
                ),
                StrokeSpec(paint = paint, width = width),
            )
        }
        out += DrawOp.Restore
    }

    /** Paints a chart's geometry in the frame's own space; chart text goes through the text engine separately. */
    private fun chart(drawing: ChartDrawing, origin: SceneRect?, out: MutableList<DrawOp>) {
        origin ?: return
        val box = SceneRect(0.0, 0.0, origin.width, origin.height)
        val ops = ArrayList<DrawOp>()
        for (shape in drawing.shapes) {
            val path = pathOf(shape.points, shape.closed) ?: continue
            paintOf(shape.fill, box)?.let { ops += DrawOp.FillPath(path, it) }
            shape.line?.let { strokeOf(it, box) }?.let { ops += DrawOp.StrokePath(path, it) }
        }
        if (ops.isEmpty()) return
        out += DrawOp.Save
        out += DrawOp.Concat(Affine.translate(origin.x, origin.y))
        out += ops
        out += DrawOp.Restore
    }

    private fun pathOf(points: List<ScenePoint>, closed: Boolean): PathData? {
        if (points.size < 2) return null
        val segments = ArrayList<PathSegment>(points.size + 1)
        segments += PathSegment.MoveTo(points[0].x.toFloat(), points[0].y.toFloat())
        for (index in 1 until points.size) {
            val point = points[index]
            segments += PathSegment.LineTo(point.x.toFloat(), point.y.toFloat())
        }
        if (closed) segments += PathSegment.Close
        return PathData(segments)
    }

    /** The picture's clip outline in its own frame space, when its mask is anything but a plain rectangle. */
    private fun maskOf(picture: ScenePicture): PathData? {
        val masked = picture.customGeometry != null || picture.mask?.preset?.let { it != "rect" } == true
        if (!masked) return null
        return geometry.outline(picture)?.let(::pathOf)
    }

    private fun outline(outline: PresetOutline, format: SceneFormat, transform: Affine, out: MutableList<DrawOp>) {
        val body = mutableListOf<DrawOp>()
        val box = SceneRect(0.0, 0.0, outline.textRect.width, outline.textRect.height)
        val stroke = strokeOf(format.line, box)
        for (contour in outline.paths) {
            val path = pathOf(contour)
            fillOf(format.fill, contour.fill, box)?.let { body += DrawOp.FillPath(path, it) }
            if (contour.stroke && stroke != null) body += DrawOp.StrokePath(path, stroke)
        }
        if (body.isEmpty()) return
        out += DrawOp.Save
        out += DrawOp.Concat(transform)
        out += body
        out += DrawOp.Restore
    }

    private fun fillOf(fill: ScenePaint?, mode: PresetFillMode, box: SceneRect): PaintSpec? = when (fill) {
        is ScenePaint.Solid -> mode.adjust(fill.color)?.let { PaintSpec.Solid(it) }
        is ScenePaint.Linear -> if (mode == PresetFillMode.NONE) null else PaintSpecs.linear(fill.gradient, box)
        is ScenePaint.Radial -> if (mode == PresetFillMode.NONE) null else PaintSpecs.radial(fill.gradient, box)
        is ScenePaint.Pattern -> if (mode == PresetFillMode.NONE) null else PaintSpecs.of(fill, box)
        else -> null
    }

    private fun paintOf(paint: ScenePaint?, box: SceneRect): PaintSpec? = PaintSpecs.of(paint, box)

    private fun strokeOf(line: SceneLine?, box: SceneRect): StrokeSpec? {
        if (line == null) return null
        val paint = paintOf(line.paint, box) ?: return null
        val width = Strokes.resolvedWidthEmu(line.widthEmu, DEFAULT_LINE_EMU)
        if (width <= 0.0) return null
        return StrokeSpec(
            paint = paint,
            width = width.toFloat(),
            cap = line.cap,
            join = line.join,
            miterLimit = (line.miterLimit / PERCENT_UNIT).toFloat().coerceAtLeast(0f),
            dash = Strokes.dashPattern(line, width).map { it.toFloat() },
        )
    }

    private fun pathOf(outline: PresetOutline): PathData =
        PathData(outline.paths.flatMap { pathOf(it).segments })

    private fun pathOf(contour: PresetOutlinePath): PathData =
        PathData(contour.segments.map(::segmentOf))

    private fun segmentOf(segment: PresetSegment): PathSegment = when (segment) {
        is PresetSegment.MoveTo -> PathSegment.MoveTo(segment.x.toFloat(), segment.y.toFloat())
        is PresetSegment.LineTo -> PathSegment.LineTo(segment.x.toFloat(), segment.y.toFloat())
        is PresetSegment.QuadTo -> PathSegment.QuadTo(
            segment.controlX.toFloat(),
            segment.controlY.toFloat(),
            segment.x.toFloat(),
            segment.y.toFloat(),
        )
        is PresetSegment.CubicTo -> PathSegment.CubicTo(
            segment.control1X.toFloat(),
            segment.control1Y.toFloat(),
            segment.control2X.toFloat(),
            segment.control2Y.toFloat(),
            segment.x.toFloat(),
            segment.y.toFloat(),
        )
        PresetSegment.Close -> PathSegment.Close
    }

    private fun flipsOf(preset: ScenePreset?): Pair<Boolean, Boolean> =
        (preset?.flipH == true) to (preset?.flipV == true)

    private fun rotationOf(transform: XNode?): Double =
        transform?.attribute("rot")?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: 0.0

    private fun isOn(transform: XNode?, name: String): Boolean {
        val value = transform?.attribute(name) ?: return false
        return value == "1" || value.equals("true", ignoreCase = true)
    }

    private fun isDrawable(frame: SceneRect): Boolean =
        frame.width.isFinite() && frame.height.isFinite() &&
            frame.width >= 0.0 && frame.height >= 0.0 &&
            (frame.width > 0.0 || frame.height > 0.0)

    private fun PictureCrop.isNegative(): Boolean = left < 0.0 || top < 0.0 || right < 0.0 || bottom < 0.0

    private fun SceneLine?.hasArrows(): Boolean =
        this != null && (headEnd.type != ArrowType.NONE || tailEnd.type != ArrowType.NONE)

    private fun SceneFormat?.isUnsupported(): Boolean =
        this != null && (
            fill is ScenePaint.Complex ||
                line?.paint is ScenePaint.Complex ||
                (line != null && line.compound != StrokeCompound.SINGLE) ||
                effects?.children?.isNotEmpty() == true
            )

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val PERCENT_UNIT = 100_000.0
        const val DEFAULT_LINE_EMU = 9_525.0
    }
}
