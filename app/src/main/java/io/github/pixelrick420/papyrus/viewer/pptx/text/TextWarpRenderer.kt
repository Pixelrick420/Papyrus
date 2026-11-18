package io.github.pixelrick420.papyrus.viewer.pptx.text

import io.github.pixelrick420.papyrus.viewer.pptx.PathBuilder
import io.github.pixelrick420.papyrus.viewer.pptx.PresetGeometry
import io.github.pixelrick420.papyrus.viewer.pptx.PresetSegment
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePoint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Maps laid-out text onto the top and bottom paths of a vendored `prstTxWarp` preset. */
internal class TextWarpRenderer(
    private val warps: PresetGeometry,
    private val builder: PathBuilder = PathBuilder(),
) {

    fun warp(
        lines: List<TextLine>,
        properties: TextBodyProperties,
        frameWidthEmu: Double,
        frameHeightEmu: Double,
    ): List<TextLine> {
        val warp = properties.warp ?: return lines
        val shape = warps.shape(warp.preset) ?: return lines
        val widthPt = (frameWidthEmu - properties.insets.left - properties.insets.right)
            .coerceAtLeast(0.0) / TextLayoutEngine.EMU_PER_POINT
        val heightPt = (frameHeightEmu - properties.insets.top - properties.insets.bottom)
            .coerceAtLeast(0.0) / TextLayoutEngine.EMU_PER_POINT
        if (widthPt <= 0.0 || heightPt <= 0.0) return lines
        val outline = builder.build(shape, widthPt, heightPt, warp.adjusts) ?: return lines
        val top = outline.paths.firstOrNull()?.let { WarpPath(it.segments) } ?: return lines
        val bottom = outline.paths.getOrNull(1)?.let { WarpPath(it.segments) }
        val boxes = lines.flatMap { it.characters }
        if (boxes.isEmpty()) return lines
        val left = boxes.minOf { it.leftPt }
        val right = boxes.maxOf { it.rightPt }
        val upper = boxes.minOf { it.topPt }
        val lower = boxes.maxOf { it.bottomPt }
        val textWidth = right - left
        val textHeight = lower - upper
        if (textWidth <= 0.0 || textHeight <= 0.0) return lines

        val boxesByStart = boxes.associateBy { it.start }
        val warped = HashMap<Int, WarpedCharacter>(boxes.size)
        for (box in boxes) {
            val x = ((box.leftPt + box.rightPt) / 2.0 - left) / textWidth
            val y = ((box.topPt + box.bottomPt) / 2.0 - upper) / textHeight
            warped[box.start] = warpedCharacter(box, x, y, top, bottom, textHeight) ?: return lines
        }
        return lines.map { line -> warpedLine(line, warped, boxesByStart) }
    }

    private fun warpedCharacter(
        box: CharacterBox,
        x: Double,
        y: Double,
        top: WarpPath,
        bottom: WarpPath?,
        textHeight: Double,
    ): WarpedCharacter? {
        val fraction = x.coerceIn(0.0, 1.0)
        val vertical = y.coerceIn(0.0, 1.0)
        val start = top.pointAndTangent(fraction) ?: return null
        val point: ScenePoint
        val angle: Double
        if (bottom == null) {
            val offset = (vertical - 0.5) * textHeight
            val radians = Math.toRadians(start.second)
            point = ScenePoint(
                start.first.x - sin(radians) * offset,
                start.first.y + cos(radians) * offset,
            )
            angle = start.second
        } else {
            val end = bottom.pointAndTangent(fraction) ?: return null
            point = ScenePoint(
                start.first.x + (end.first.x - start.first.x) * vertical,
                start.first.y + (end.first.y - start.first.y) * vertical,
            )
            angle = blendAngle(start.second, end.second, vertical)
        }
        val width = box.rightPt - box.leftPt
        val height = box.bottomPt - box.topPt
        val radians = Math.toRadians(angle)
        return WarpedCharacter(
            point = point,
            angle = angle,
            halfWidth = (abs(cos(radians)) * width + abs(sin(radians)) * height) / 2.0,
            halfHeight = (abs(sin(radians)) * width + abs(cos(radians)) * height) / 2.0,
        )
    }

    private fun warpedLine(
        line: TextLine,
        warped: Map<Int, WarpedCharacter>,
        boxes: Map<Int, CharacterBox>,
    ): TextLine {
        val runs = line.runs.flatMap { run -> warpedRuns(run, warped, boxes) }
        val characters = line.characters.mapNotNull { box ->
            warped[box.start]?.let { placed ->
                CharacterBox(
                    box.start,
                    box.end,
                    placed.point.x - placed.halfWidth,
                    placed.point.x + placed.halfWidth,
                    placed.point.y - placed.halfHeight,
                    placed.point.y + placed.halfHeight,
                )
            }
        }
        if (characters.isEmpty() || runs.isEmpty()) return line
        return TextLine(
            index = line.index,
            paragraph = line.paragraph,
            start = line.start,
            end = line.end,
            leftPt = characters.minOf { it.leftPt },
            topPt = characters.minOf { it.topPt },
            widthPt = characters.maxOf { it.rightPt } - characters.minOf { it.leftPt },
            heightPt = characters.maxOf { it.bottomPt } - characters.minOf { it.topPt },
            baselinePt = characters.maxOf { it.bottomPt },
            runs = runs,
            characters = characters,
        )
    }

    private fun warpedRuns(
        run: GlyphRun,
        warped: Map<Int, WarpedCharacter>,
        boxes: Map<Int, CharacterBox>,
    ): List<GlyphRun> {
        val result = ArrayList<GlyphRun>()
        var offset = run.start
        while (offset < run.end) {
            val end = minOf(run.end, offset + 1)
            val placed = warped[offset] ?: break
            val box = boxes[offset] ?: break
            val radians = Math.toRadians(placed.angle + run.rotationDeg)
            val alongX = cos(radians)
            val alongY = sin(radians)
            val normalX = -alongY
            val normalY = alongX
            val centerOffset = (box.topPt + box.bottomPt) / 2.0 - run.baselinePt
            val leftX = placed.point.x - alongX * placed.halfWidth - normalX * centerOffset
            val leftY = placed.point.y - alongY * placed.halfWidth - normalY * centerOffset
            result += GlyphRun(
                start = offset,
                end = end,
                leftPt = leftX,
                rightPt = leftX + alongX * placed.halfWidth * 2.0,
                baselinePt = leftY,
                font = run.font,
                sizePt = run.sizePt,
                properties = run.properties,
                rtl = run.rtl,
                text = run.text.substring(offset - run.start, end - run.start),
                decorations = run.decorations,
                rotationDeg = placed.angle + run.rotationDeg,
            )
            offset = end
        }
        return result
    }

    private fun blendAngle(from: Double, to: Double, fraction: Double): Double {
        val turn = ((to - from + 540.0) % 360.0) - 180.0
        return from + turn * fraction
    }

    private data class WarpedCharacter(
        val point: ScenePoint,
        val angle: Double,
        val halfWidth: Double,
        val halfHeight: Double,
    )

    private class WarpPath(segments: List<PresetSegment>) {

        private val points: List<ScenePoint> = flatten(segments)

        private val lengths = DoubleArray(points.size)

        private val total: Double

        init {
            for (index in 1 until points.size) {
                lengths[index] = lengths[index - 1] + distance(points[index - 1], points[index])
            }
            total = lengths.lastOrNull() ?: 0.0
        }

        fun pointAndTangent(fraction: Double): Pair<ScenePoint, Double>? {
            if (points.size < 2 || total <= 0.0) return null
            val target = fraction * total
            var index = 1
            while (index < lengths.lastIndex && lengths[index] < target) index++
            val start = points[index - 1]
            val end = points[index]
            val span = lengths[index] - lengths[index - 1]
            val ratio = if (span > 0.0) ((target - lengths[index - 1]) / span).coerceIn(0.0, 1.0) else 0.0
            val point = ScenePoint(
                start.x + (end.x - start.x) * ratio,
                start.y + (end.y - start.y) * ratio,
            )
            val angle = Math.toDegrees(atan2(end.y - start.y, end.x - start.x))
            return point to angle
        }

        private fun flatten(segments: List<PresetSegment>): List<ScenePoint> {
            val points = ArrayList<ScenePoint>()
            var start: ScenePoint? = null
            var current = ScenePoint(0.0, 0.0)
            fun add(point: ScenePoint) {
                if (!point.x.isFinite() || !point.y.isFinite()) return
                if (points.isEmpty() || distance(points.last(), point) > 1e-9) points += point
                current = point
            }
            for (segment in segments) {
                when (segment) {
                    is PresetSegment.MoveTo -> {
                        val point = ScenePoint(segment.x, segment.y)
                        add(point)
                        start = point
                    }
                    is PresetSegment.LineTo -> add(ScenePoint(segment.x, segment.y))
                    is PresetSegment.QuadTo -> {
                        for (step in 1..CURVE_STEPS) {
                            val t = step.toDouble() / CURVE_STEPS
                            add(
                                ScenePoint(
                                    quadratic(current.x, segment.controlX, segment.x, t),
                                    quadratic(current.y, segment.controlY, segment.y, t),
                                ),
                            )
                        }
                    }
                    is PresetSegment.CubicTo -> {
                        for (step in 1..CURVE_STEPS) {
                            val t = step.toDouble() / CURVE_STEPS
                            add(
                                ScenePoint(
                                    cubic(current.x, segment.control1X, segment.control2X, segment.x, t),
                                    cubic(current.y, segment.control1Y, segment.control2Y, segment.y, t),
                                ),
                            )
                        }
                    }
                    PresetSegment.Close -> start?.let(::add)
                }
            }
            return points
        }

        private fun quadratic(begin: Double, control: Double, end: Double, t: Double): Double {
            val inverse = 1.0 - t
            return inverse * inverse * begin + 2.0 * inverse * t * control + t * t * end
        }

        private fun cubic(
            begin: Double,
            first: Double,
            second: Double,
            end: Double,
            t: Double,
        ): Double {
            val inverse = 1.0 - t
            return inverse * inverse * inverse * begin +
                3.0 * inverse * inverse * t * first +
                3.0 * inverse * t * t * second +
                t * t * t * end
        }

        private fun distance(first: ScenePoint, second: ScenePoint): Double =
            hypot(second.x - first.x, second.y - first.y)

        private companion object {
            const val CURVE_STEPS = 24
        }
    }
}
