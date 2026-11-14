package io.github.pixelrick420.papyrus.viewer.pptx

import kotlin.math.max

/** One evaluated path segment in shape coordinates (EMU). */
internal sealed interface PresetSegment {
    data class MoveTo(val x: Double, val y: Double) : PresetSegment

    data class LineTo(val x: Double, val y: Double) : PresetSegment

    data class QuadTo(val controlX: Double, val controlY: Double, val x: Double, val y: Double) :
        PresetSegment

    data class CubicTo(
        val control1X: Double,
        val control1Y: Double,
        val control2X: Double,
        val control2Y: Double,
        val x: Double,
        val y: Double,
    ) : PresetSegment

    object Close : PresetSegment
}

/** One path of an evaluated preset: its segments and how the paint pass fills and strokes it. */
internal class PresetOutlinePath(
    val segments: List<PresetSegment>,
    val fill: PresetFillMode,
    val stroke: Boolean,
)

/** A preset's evaluated outline in shape coordinates, plus the rectangle its text is laid out in. */
internal class PresetOutline(val paths: List<PresetOutlinePath>, val textRect: SceneRect)

/**
 * Turns a [PresetShape] into shape-space geometry for one box size. The shape's `avLst` overrides the
 * preset's adjust defaults, and a path that declares its own `w`/`h` space is scaled onto the box.
 */
internal class PathBuilder(private val limits: PptxLimits = PptxLimits()) {

    /** Null when a coordinate or an arc is non-finite, as a zero-radius arc is. */
    fun build(
        shape: PresetShape,
        width: Double,
        height: Double,
        adjusts: Map<String, String> = emptyMap(),
    ): PresetOutline? {
        val evaluator = GuideEvaluator(width, height, limits)
        val scope = evaluator.evaluate(shape.adjusts).toMutableMap()
        for (guide in shape.adjusts) {
            val formula = adjusts[guide.name] ?: continue
            evaluator.evaluate(formula, scope)?.let { scope[guide.name] = it }
        }
        val evaluated = evaluator.evaluate(shape.guides, scope)
        val paths = shape.paths.map { path ->
            PresetOutlinePath(
                segments = segments(path, evaluated, width, height) ?: return null,
                fill = path.fill,
                stroke = path.stroke,
            )
        }
        if (paths.any { path -> path.segments.any { !it.isFinite() } }) return null
        return PresetOutline(paths, textRect(shape.textRect, evaluated, width, height))
    }

    private fun segments(
        path: PresetPath,
        scope: Map<String, Double>,
        width: Double,
        height: Double,
    ): List<PresetSegment>? {
        val scaleX = if (path.width != null && path.width > 0) width / path.width else 1.0
        val scaleY = if (path.height != null && path.height > 0) height / path.height else 1.0
        val segments = mutableListOf<PresetSegment>()
        var currentX = 0.0
        var currentY = 0.0
        var startX = 0.0
        var startY = 0.0
        var open = false

        fun x(token: String): Double = value(token, scope)

        fun y(token: String): Double = value(token, scope)

        // A drawing command after a close continues from the closed subpath's start, as Path2D does.
        fun resume() {
            if (open) return
            open = true
            currentX = startX
            currentY = startY
            segments += PresetSegment.MoveTo(startX, startY)
        }

        for (command in path.commands) {
            when (command) {
                is PresetCommand.MoveTo -> {
                    currentX = x(command.x)
                    currentY = y(command.y)
                    startX = currentX
                    startY = currentY
                    open = true
                    segments += PresetSegment.MoveTo(currentX, currentY)
                }
                is PresetCommand.LineTo -> {
                    resume()
                    currentX = x(command.x)
                    currentY = y(command.y)
                    segments += PresetSegment.LineTo(currentX, currentY)
                }
                is PresetCommand.QuadTo -> {
                    resume()
                    val controlX = x(command.controlX)
                    val controlY = y(command.controlY)
                    currentX = x(command.x)
                    currentY = y(command.y)
                    segments += PresetSegment.QuadTo(controlX, controlY, currentX, currentY)
                }
                is PresetCommand.CubicTo -> {
                    resume()
                    val control1X = x(command.control1X)
                    val control1Y = y(command.control1Y)
                    val control2X = x(command.control2X)
                    val control2Y = y(command.control2Y)
                    currentX = x(command.x)
                    currentY = y(command.y)
                    segments += PresetSegment.CubicTo(
                        control1X, control1Y, control2X, control2Y, currentX, currentY,
                    )
                }
                is PresetCommand.ArcTo -> {
                    val swingAngle = value(command.swingAngle, scope)
                    if (!ArcMath.isSweepWithinCap(swingAngle)) return null
                    val afterClose = segments.isNotEmpty() && !open
                    val arc = ArcMath.arcTo(
                        lastX = currentX,
                        lastY = currentY,
                        widthRadius = x(command.widthRadius),
                        heightRadius = y(command.heightRadius),
                        startAngle = value(command.startAngle, scope),
                        swingAngle = swingAngle,
                        started = segments.isNotEmpty(),
                        afterClose = afterClose,
                    )
                    if (arc.isNotEmpty()) {
                        if (afterClose) segments += PresetSegment.MoveTo(currentX, currentY)
                        segments += arc
                        open = true
                        val end = arc.last().endpoint()!!
                        currentX = end.first
                        currentY = end.second
                    }
                }
                PresetCommand.Close -> {
                    segments += PresetSegment.Close
                    open = false
                    currentX = startX
                    currentY = startY
                }
            }
        }
        return segments.map { it.scaled(scaleX, scaleY) }
    }

    private fun textRect(
        rect: PresetTextRect?,
        scope: Map<String, Double>,
        width: Double,
        height: Double,
    ): SceneRect {
        if (rect == null) return SceneRect(0.0, 0.0, width, height)
        val left = value(rect.left, scope)
        val top = value(rect.top, scope)
        val right = value(rect.right, scope)
        val bottom = value(rect.bottom, scope)
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite()) {
            return SceneRect(0.0, 0.0, width, height)
        }
        return SceneRect(left, top, max(0.0, right - left), max(0.0, bottom - top))
    }

    private fun value(token: String, scope: Map<String, Double>): Double =
        token.toDoubleOrNull() ?: scope[token] ?: Double.NaN

    private fun PresetSegment.scaled(scaleX: Double, scaleY: Double): PresetSegment = when (this) {
        is PresetSegment.MoveTo -> PresetSegment.MoveTo(x * scaleX, y * scaleY)
        is PresetSegment.LineTo -> PresetSegment.LineTo(x * scaleX, y * scaleY)
        is PresetSegment.QuadTo -> PresetSegment.QuadTo(
            controlX * scaleX, controlY * scaleY, x * scaleX, y * scaleY,
        )
        is PresetSegment.CubicTo -> PresetSegment.CubicTo(
            control1X * scaleX, control1Y * scaleY,
            control2X * scaleX, control2Y * scaleY,
            x * scaleX, y * scaleY,
        )
        PresetSegment.Close -> PresetSegment.Close
    }

    private fun PresetSegment.endpoint(): Pair<Double, Double>? = when (this) {
        is PresetSegment.MoveTo -> x to y
        is PresetSegment.LineTo -> x to y
        is PresetSegment.QuadTo -> x to y
        is PresetSegment.CubicTo -> x to y
        PresetSegment.Close -> null
    }

    private fun PresetSegment.isFinite(): Boolean = when (this) {
        is PresetSegment.MoveTo -> x.isFinite() && y.isFinite()
        is PresetSegment.LineTo -> x.isFinite() && y.isFinite()
        is PresetSegment.QuadTo -> controlX.isFinite() && controlY.isFinite() &&
            x.isFinite() && y.isFinite()
        is PresetSegment.CubicTo -> control1X.isFinite() && control1Y.isFinite() &&
            control2X.isFinite() && control2Y.isFinite() && x.isFinite() && y.isFinite()
        PresetSegment.Close -> true
    }
}
