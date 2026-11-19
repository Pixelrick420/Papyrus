package io.github.pixelrick420.papyrus.viewer.pptx.render

import io.github.pixelrick420.papyrus.viewer.pptx.Affine
import io.github.pixelrick420.papyrus.viewer.pptx.ArrowType
import io.github.pixelrick420.papyrus.viewer.pptx.EMU_PER_PIXEL
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
import io.github.pixelrick420.papyrus.viewer.pptx.SceneEffect
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
import io.github.pixelrick420.papyrus.viewer.pptx.stretchRect
import io.github.pixelrick420.papyrus.viewer.pptx.StrokeCompound
import io.github.pixelrick420.papyrus.viewer.pptx.Strokes
import io.github.pixelrick420.papyrus.viewer.pptx.XNode
import io.github.pixelrick420.papyrus.viewer.pptx.chart.ChartDrawing
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

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
        text?.layoutTables(scene)
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
        val rotation = rotationOf(shape.transform)
        outline(
            outline,
            format,
            Affine.forShape(frame, rotation = rotation, flipH = flipH, flipV = flipV),
            Affine.forShape(frame, rotation = 0.0, flipH = flipH, flipV = flipV),
            rotation / ROTATION_UNIT,
            out,
        )
    }

    private fun connector(connector: SceneConnector, out: MutableList<DrawOp>) {
        val frame = connector.frame?.takeIf(::isDrawable) ?: return
        val format = connector.format ?: return
        if (format.isUnsupported()) return
        val outline = geometry.outline(connector) ?: return
        val (flipH, flipV) = flipsOf(connector.preset)
        val rotation = rotationOf(connector.transform)
        val transform = Affine.forShape(frame, rotation = rotation, flipH = flipH, flipV = flipV)
        val line = format.line
        if (line != null && line.hasArrows()) {
            arrowedConnector(outline, line, transform, out)
            return
        }
        outline(
            outline,
            format,
            transform,
            Affine.forShape(frame, rotation = 0.0, flipH = flipH, flipV = flipV),
            rotation / ROTATION_UNIT,
            out,
        )
    }

    private fun arrowedConnector(
        outline: PresetOutline,
        line: SceneLine,
        transform: Affine,
        out: MutableList<DrawOp>,
    ) {
        val box = SceneRect(0.0, 0.0, outline.textRect.width, outline.textRect.height)
        val stroke = strokeOf(line, box) ?: return
        val body = mutableListOf<DrawOp>()
        for (contour in outline.paths) {
            val points = pointsOf(contour)
            pathOf(Strokes.shaft(line, points, DEFAULT_LINE_EMU), closed = false)?.let {
                body += DrawOp.StrokePath(it, stroke)
            }
            for (atStart in booleanArrayOf(true, false)) {
                val arrow = Strokes.arrowGeometry(line, atStart, points, DEFAULT_LINE_EMU) ?: continue
                val path = pathOf(arrow.points, arrow.closed) ?: continue
                if (arrow.filled) {
                    body += DrawOp.FillPath(path, stroke.paint)
                } else {
                    body += DrawOp.StrokePath(path, stroke)
                }
            }
        }
        if (body.isEmpty()) return
        out += DrawOp.Save
        out += DrawOp.Concat(transform)
        out += body
        out += DrawOp.Restore
    }

    private fun pointsOf(contour: PresetOutlinePath): List<ScenePoint> = buildList {
        for (segment in contour.segments) {
            when (segment) {
                is PresetSegment.MoveTo -> add(ScenePoint(segment.x, segment.y))
                is PresetSegment.LineTo -> add(ScenePoint(segment.x, segment.y))
                is PresetSegment.QuadTo -> add(ScenePoint(segment.x, segment.y))
                is PresetSegment.CubicTo -> add(ScenePoint(segment.x, segment.y))
                PresetSegment.Close -> Unit
            }
        }
    }

    private fun picture(picture: ScenePicture, out: MutableList<DrawOp>, pictures: SlidePictureResolver) {
        val frame = picture.frame?.takeIf { it.width > 0.0 && it.height > 0.0 } ?: return
        if (picture.format.isUnsupported()) return
        val source = picture.source ?: return
        val fill = source.fill
        if (fill !is PictureFill.Stretch) return
        val raster = pictures.imageOf(picture) ?: return
        val local = SceneRect(0.0, 0.0, frame.width, frame.height)
        val regions = pictureRegions(source.crop, fill.insets, local, raster.bounds) ?: return
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
            src = regions.first,
            dst = regions.second,
            effects = ImageEffects(alpha = source.alpha.toFloat(), clip = maskOf(picture), recolor = source.effects),
        )
        out += DrawOp.Restore
    }

    /** Maps the source crop through a stretch inset, clipped to the frame; negative sides stay transparent. */
    private fun pictureRegions(
        crop: PictureCrop,
        insets: PictureInsets,
        frame: SceneRect,
        bounds: ImageBounds,
    ): Pair<RectF, RectF>? {
        if (!crop.isVisible) return null
        val stretch = stretchRect(frame, insets)
        if (stretch.width <= 0.0 || stretch.height <= 0.0) return null
        val scaleX = stretch.width / crop.width
        val scaleY = stretch.height / crop.height
        val firstX = maxOf(0.0, crop.left - stretch.x / scaleX)
        val lastX = minOf(1.0, crop.left + (frame.width - stretch.x) / scaleX)
        val firstY = maxOf(0.0, crop.top - stretch.y / scaleY)
        val lastY = minOf(1.0, crop.top + (frame.height - stretch.y) / scaleY)
        if (lastX <= firstX || lastY <= firstY) return null
        val src = RectF(
            (firstX * bounds.width).toFloat(),
            (firstY * bounds.height).toFloat(),
            (lastX * bounds.width).toFloat(),
            (lastY * bounds.height).toFloat(),
        )
        val dst = RectF(
            (stretch.x + (firstX - crop.left) * scaleX).toFloat(),
            (stretch.y + (firstY - crop.top) * scaleY).toFloat(),
            (stretch.x + (lastX - crop.left) * scaleX).toFloat(),
            (stretch.y + (lastY - crop.top) * scaleY).toFloat(),
        )
        return src to dst
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

    private fun outline(
        outline: PresetOutline,
        format: SceneFormat,
        transform: Affine,
        flatTransform: Affine,
        rotationDegrees: Double,
        out: MutableList<DrawOp>,
    ) {
        val effects = format.effects?.items.orEmpty()
        val shadows = effects.filterIsInstance<SceneEffect.Shadow>()
        val rotating = shadows.filter { it.rotateWithShape && !it.inner }
        val flat = shadows.filter { !it.rotateWithShape && !it.inner }
        val inner = shadows.filter { it.inner }
        val glows = effects.filterIsInstance<SceneEffect.Glow>()
        val softEdges = effects.filterIsInstance<SceneEffect.SoftEdge>()
        val blurred = effects.any { it is SceneEffect.Blur }
        val reflections = effects.filterIsInstance<SceneEffect.Reflection>()
        if (flat.isNotEmpty()) {
            out += DrawOp.Save
            out += DrawOp.Concat(flatTransform)
            for (contour in outline.paths) {
                val path = pathOf(contour)
                for (shadow in flat) out += DrawOp.ShapeShadow(path, shadowSpec(shadow))
            }
            out += DrawOp.Restore
        }
        val body = mutableListOf<DrawOp>()
        val box = SceneRect(0.0, 0.0, outline.textRect.width, outline.textRect.height)
        val stroke = strokeOf(format.line, box, rotationDegrees)
        for (contour in outline.paths) {
            val path = pathOf(contour)
            for (shadow in rotating) body += DrawOp.ShapeShadow(path, shadowSpec(shadow))
            for (glow in glows) {
                body += DrawOp.Glow(path, glow.color, (glow.radiusEmu / EMU_PER_PIXEL).toFloat())
            }
            fillOf(format.fill, contour.fill, box, rotationDegrees)?.let { paint ->
                val edge = softEdges.lastOrNull()
                when {
                    edge != null -> body += DrawOp.SoftEdge(path, paint, (edge.radiusEmu / EMU_PER_PIXEL).toFloat())
                    blurred -> body += DrawOp.Blur(path, paint, (DEFAULT_BLUR_EMU / EMU_PER_PIXEL).toFloat())
                    else -> body += DrawOp.FillPath(path, paint)
                }
            }
            if (contour.stroke && stroke != null) body += DrawOp.StrokePath(path, stroke)
            if (reflections.isNotEmpty()) {
                fillOf(format.fill, contour.fill, box, rotationDegrees)?.let { paint ->
                    for (reflection in reflections) body += DrawOp.Reflection(path, paint, reflectionSpec(reflection))
                }
            }
            for (shadow in inner) body += DrawOp.ShapeShadow(path, shadowSpec(shadow))
        }
        if (body.isEmpty()) return
        out += DrawOp.Save
        out += DrawOp.Concat(transform)
        out += body
        out += DrawOp.Restore
    }

    private fun shadowSpec(shadow: SceneEffect.Shadow): ShapeShadowSpec {
        val direction = shadow.align?.let(ALIGN_DIRECTIONS::get) ?: shadow.directionDegrees
        val radians = Math.toRadians(direction)
        val distance = if (shadow.align == "ctr") 0.0 else shadow.distanceEmu
        return ShapeShadowSpec(
            color = shadow.color,
            blur = (shadow.blurEmu / EMU_PER_PIXEL).toFloat(),
            transform = Affine(
                a = shadow.scaleX,
                b = tan(Math.toRadians(shadow.skewY)),
                c = tan(Math.toRadians(shadow.skewX)),
                d = shadow.scaleY,
                e = cos(radians) * distance / EMU_PER_PIXEL,
                f = sin(radians) * distance / EMU_PER_PIXEL,
            ),
            inner = shadow.inner,
        )
    }

    private fun reflectionSpec(reflection: SceneEffect.Reflection): ReflectionSpec = ReflectionSpec(
        blur = (reflection.blurEmu / EMU_PER_PIXEL).toFloat(),
        distance = (reflection.distanceEmu / EMU_PER_PIXEL).toFloat(),
        directionDegrees = reflection.directionDegrees.toFloat(),
        startAlpha = reflection.startAlpha.toFloat(),
        endAlpha = reflection.endAlpha.toFloat(),
        startPosition = reflection.startPosition.toFloat(),
        endPosition = reflection.endPosition.toFloat(),
    )

    private fun fillOf(
        fill: ScenePaint?,
        mode: PresetFillMode,
        box: SceneRect,
        rotationDegrees: Double = 0.0,
    ): PaintSpec? = when (fill) {
        is ScenePaint.Solid -> mode.adjust(fill.color)?.let { PaintSpec.Solid(it) }
        is ScenePaint.Linear -> if (mode == PresetFillMode.NONE) null else PaintSpecs.linear(fill.gradient, box, rotationDegrees)
        is ScenePaint.Radial -> if (mode == PresetFillMode.NONE) null else PaintSpecs.radial(fill.gradient, box, rotationDegrees)
        is ScenePaint.Path -> if (mode == PresetFillMode.NONE) null else PaintSpecs.path(fill.gradient, box, rotationDegrees)
        is ScenePaint.Pattern -> if (mode == PresetFillMode.NONE) null else PaintSpecs.of(fill, box, rotationDegrees)
        else -> null
    }

    private fun paintOf(paint: ScenePaint?, box: SceneRect, rotationDegrees: Double = 0.0): PaintSpec? =
        PaintSpecs.of(paint, box, rotationDegrees)

    private fun strokeOf(line: SceneLine?, box: SceneRect, rotationDegrees: Double = 0.0): StrokeSpec? {
        if (line == null) return null
        val paint = paintOf(line.paint, box, rotationDegrees) ?: return null
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

    private fun SceneLine?.hasArrows(): Boolean =
        this != null && (headEnd.type != ArrowType.NONE || tailEnd.type != ArrowType.NONE)

    private fun SceneFormat?.isUnsupported(): Boolean =
        this != null && (
            fill is ScenePaint.Complex ||
                line?.paint is ScenePaint.Complex ||
                (line != null && line.compound != StrokeCompound.SINGLE)
            )

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
        const val PERCENT_UNIT = 100_000.0
        const val ROTATION_UNIT = 60_000.0
        const val DEFAULT_LINE_EMU = 9_525.0
        const val DEFAULT_BLUR_EMU = 50_800.0
        val ALIGN_DIRECTIONS = mapOf(
            "t" to -90.0,
            "tr" to -45.0,
            "r" to 0.0,
            "br" to 45.0,
            "b" to 90.0,
            "bl" to 135.0,
            "l" to 180.0,
            "tl" to 225.0,
            "ctr" to 0.0,
        )
    }
}
