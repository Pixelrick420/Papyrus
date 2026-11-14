package io.github.pixelrick420.papyrus.viewer.pptx

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * DrawingML `a:arcTo` ported from POI, which appends a `java.awt.geom.Arc2D` to the path. The arc
 * is centred so it passes through the current point at the start angle, and the emitted cubics use
 * AWT's own arithmetic and angle conversion; matching those bit for bit is what lets the join with
 * the previous segment be elided exactly when AWT elides it.
 */
internal object ArcMath {

    /** A swing longer than this many full turns is hostile geometry, not a curve to draw. */
    const val MAX_SWING_TURNS = 16.0

    private const val UNITS_PER_DEGREE = 60_000.0
    private const val FULL_TURN = 360.0
    private const val QUARTER_TURN = 90.0
    private const val HALF_PI = PI / 2
    private const val QUARTER_CIRCLE = 0.5522847498307933
    private const val ANGLE_BUCKET = 90.0

    fun isSweepWithinCap(swingAngle: Double): Boolean =
        abs(swingAngle) <= MAX_SWING_TURNS * FULL_TURN * UNITS_PER_DEGREE

    /**
     * The segments POI appends for one arc: a join to the arc's start when the previous point is
     * not already there, then its cubics. Empty for a negative radius, as AWT yields no segments.
     */
    fun arcTo(
        lastX: Double,
        lastY: Double,
        widthRadius: Double,
        heightRadius: Double,
        startAngle: Double,
        swingAngle: Double,
        started: Boolean,
        afterClose: Boolean,
    ): List<PresetSegment> {
        if (widthRadius < 0 || heightRadius < 0) return emptyList()
        val start = startAngle / UNITS_PER_DEGREE
        val sweep = swingAngle / UNITS_PER_DEGREE
        val geometryStart = toAwtAngle(start, widthRadius, heightRadius)
        val geometrySweep = toAwtAngle(start + sweep, widthRadius, heightRadius) - geometryStart
        val radians = Math.toRadians(start)
        val theta = atan2(widthRadius * sin(radians), heightRadius * cos(radians))
        val boxX = lastX - widthRadius * cos(theta) - widthRadius
        val boxY = lastY - heightRadius * sin(theta) - heightRadius
        val centreX = boxX + widthRadius
        val centreY = boxY + heightRadius
        val angle = -Math.toRadians(geometryStart)
        val extent = -geometrySweep

        var arcSegments: Int
        var increment: Double
        var cv: Double
        if (extent >= FULL_TURN || extent <= -FULL_TURN) {
            arcSegments = 4
            increment = HALF_PI
            cv = QUARTER_CIRCLE
            if (extent < 0) {
                increment = -increment
                cv = -cv
            }
        } else {
            arcSegments = ceil(abs(extent) / QUARTER_TURN).toInt()
            increment = Math.toRadians(extent / arcSegments)
            cv = 4.0 / 3.0 * sin(increment / 2) / (1 + cos(increment / 2))
            if (cv == 0.0) arcSegments = 0
        }

        val startX = centreX + cos(angle) * widthRadius
        val startY = centreY + sin(angle) * heightRadius
        val segments = ArrayList<PresetSegment>(6)
        when {
            !started -> segments += PresetSegment.MoveTo(startX, startY)
            afterClose -> segments += PresetSegment.LineTo(startX, startY)
            startX != lastX || startY != lastY -> segments += PresetSegment.LineTo(startX, startY)
        }
        for (index in 1..arcSegments) {
            var current = angle + increment * (index - 1)
            var relX = cos(current)
            var relY = sin(current)
            val control1X = centreX + (relX - cv * relY) * widthRadius
            val control1Y = centreY + (relY + cv * relX) * heightRadius
            current += increment
            relX = cos(current)
            relY = sin(current)
            val control2X = centreX + (relX + cv * relY) * widthRadius
            val control2Y = centreY + (relY - cv * relX) * heightRadius
            segments += PresetSegment.CubicTo(
                control1X, control1Y,
                control2X, control2Y,
                centreX + relX * widthRadius,
                centreY + relY * heightRadius,
            )
        }
        return segments
    }

    /** A geometric angle becomes the ellipse's own parameter, folded into the arc's quadrant. */
    private fun toAwtAngle(start: Double, widthRadius: Double, heightRadius: Double): Double {
        val ratio = heightRadius / widthRadius
        val negated = -start
        var remainder = negated % FULL_TURN
        var base = negated - remainder
        when ((remainder / ANGLE_BUCKET).toInt()) {
            -3 -> {
                base -= FULL_TURN
                remainder += FULL_TURN
            }
            -2, -1 -> {
                base -= 180.0
                remainder += 180.0
            }
            1, 2 -> {
                base += 180.0
                remainder -= 180.0
            }
            3 -> {
                base += FULL_TURN
                remainder -= FULL_TURN
            }
        }
        return base + Math.toDegrees(atan2(tan(Math.toRadians(remainder)), ratio))
    }
}
