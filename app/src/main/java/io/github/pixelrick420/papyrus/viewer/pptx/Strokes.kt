package io.github.pixelrick420.papyrus.viewer.pptx

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

internal enum class StrokeCap {
    FLAT,
    ROUND,
    SQUARE,
    ;

    companion object {
        fun of(value: String?): StrokeCap = when (value) {
            "rnd" -> ROUND
            "sq" -> SQUARE
            else -> FLAT
        }
    }
}

internal enum class StrokeJoin {
    ROUND,
    BEVEL,
    MITER,
}

internal enum class StrokeCompound {
    SINGLE,
    DOUBLE,
    THICK_THIN,
    THIN_THICK,
    TRIPLE,
    ;

    companion object {
        fun of(value: String?): StrokeCompound = when (value) {
            "dbl" -> DOUBLE
            "thickThin" -> THICK_THIN
            "thinThick" -> THIN_THICK
            "tri" -> TRIPLE
            else -> SINGLE
        }
    }
}

internal enum class ArrowType {
    NONE,
    TRIANGLE,
    STEALTH,
    DIAMOND,
    OVAL,
    ARROW,
    ;

    companion object {
        fun of(value: String?): ArrowType = when (value) {
            "triangle" -> TRIANGLE
            "stealth" -> STEALTH
            "diamond" -> DIAMOND
            "oval" -> OVAL
            "arrow" -> ARROW
            else -> NONE
        }
    }
}

internal enum class ArrowSize(private val multiplier: Double) {
    SMALL(2.0),
    MEDIUM(3.0),
    LARGE(5.0),
    ;

    fun emu(lineWidth: Double): Double = multiplier * lineWidth

    companion object {
        fun of(value: String?): ArrowSize = when (value) {
            "sm" -> SMALL
            "lg" -> LARGE
            else -> MEDIUM
        }
    }
}

internal class ArrowEnd(
    val type: ArrowType,
    val width: ArrowSize = ArrowSize.MEDIUM,
    val length: ArrowSize = ArrowSize.MEDIUM,
) {

    fun lengthEmu(lineWidth: Double): Double = length.emu(lineWidth)

    fun widthEmu(lineWidth: Double): Double = width.emu(lineWidth)

    /** A filled head hides the shaft up to the start of the head; an open one lets the shaft reach the tip. */
    fun trimEmu(lineWidth: Double): Double =
        if (type == ArrowType.NONE || type == ArrowType.ARROW) 0.0 else lengthEmu(lineWidth)

    companion object {
        val NONE = ArrowEnd(ArrowType.NONE)
    }
}

/** One dash and the space after it, both as multiples of the line width. */
internal data class DashStop(val dash: Double, val space: Double)

/** A compound line's stroke: its width and its offset from the path's centre line, in EMU. */
internal class StrokeBand(val widthEmu: Double, val offsetEmu: Double)

/** One end's outline in EMU, ready to fill or stroke. */
internal class ArrowGeometry(val points: List<ScenePoint>, val closed: Boolean, val filled: Boolean)

internal object Strokes {

    const val DEFAULT_MITER_LIMIT = 800_000.0

    private const val PERCENT_UNIT = 100_000.0

    // OOXML preset dash and space lengths, as multiples of the line width.
    private val PRESETS: Map<String, List<Double>> = mapOf(
        "solid" to emptyList(),
        "dot" to listOf(1.0, 1.0),
        "dash" to listOf(4.0, 3.0),
        "lgDash" to listOf(8.0, 3.0),
        "dashDot" to listOf(4.0, 3.0, 1.0, 3.0),
        "lgDashDot" to listOf(8.0, 3.0, 1.0, 3.0),
        "lgDashDotDot" to listOf(8.0, 3.0, 1.0, 3.0, 1.0, 3.0),
        "sysDash" to listOf(3.0, 1.0),
        "sysDot" to listOf(1.0, 1.0),
        "sysDashDot" to listOf(3.0, 1.0, 1.0, 1.0),
        "sysDashDotDot" to listOf(3.0, 1.0, 1.0, 1.0, 1.0, 1.0),
    )

    fun presetNames(): Set<String> = PRESETS.keys

    fun resolvedWidthEmu(widthEmu: Double?, devicePixelEmu: Double): Double {
        val width = widthEmu ?: 0.0
        return if (width > 0.0) width else max(devicePixelEmu, 0.0)
    }

    fun customDash(dash: XNode): List<DashStop> =
        dash.childrenOf(OoxmlNamespace.A, "ds").mapNotNull { stop ->
            val length = stop.attribute("d")?.toDoubleOrNull() ?: return@mapNotNull null
            val space = stop.attribute("sp")?.toDoubleOrNull() ?: return@mapNotNull null
            DashStop(max(0.0, length / PERCENT_UNIT), max(0.0, space / PERCENT_UNIT))
        }

    fun dashPattern(line: SceneLine, widthEmu: Double): List<Double> {
        if (line.customDash.isNotEmpty()) {
            return line.customDash.flatMap { listOf(it.dash * widthEmu, it.space * widthEmu) }
        }
        return PRESETS[line.dash]?.map { it * widthEmu }.orEmpty()
    }

    fun bands(line: SceneLine, widthEmu: Double): List<StrokeBand> = bands(line.compound, widthEmu)

    fun bands(compound: StrokeCompound, widthEmu: Double): List<StrokeBand> {
        val wide = widthEmu / 2.0
        val third = widthEmu / 3.0
        val quarter = widthEmu / 4.0
        return when (compound) {
            StrokeCompound.SINGLE -> listOf(StrokeBand(widthEmu, 0.0))
            StrokeCompound.DOUBLE -> listOf(
                StrokeBand(third, -third),
                StrokeBand(third, third),
            )
            StrokeCompound.THICK_THIN -> listOf(
                StrokeBand(wide, -quarter),
                StrokeBand(quarter, quarter + quarter / 2.0),
            )
            StrokeCompound.THIN_THICK -> listOf(
                StrokeBand(quarter, -(quarter + quarter / 2.0)),
                StrokeBand(wide, quarter),
            )
            StrokeCompound.TRIPLE -> listOf(
                StrokeBand(third, -third),
                StrokeBand(third, 0.0),
                StrokeBand(third, third),
            )
        }
    }

    fun shaft(line: SceneLine, points: List<ScenePoint>, devicePixelEmu: Double): List<ScenePoint> {
        if (points.size < 2) return points
        val width = resolvedWidthEmu(line.widthEmu, devicePixelEmu)
        return trim(points, line.headEnd.trimEmu(width), line.tailEnd.trimEmu(width))
    }

    fun arrowGeometry(
        line: SceneLine,
        atStart: Boolean,
        points: List<ScenePoint>,
        devicePixelEmu: Double,
    ): ArrowGeometry? {
        if (points.size < 2) return null
        val end = if (atStart) line.headEnd else line.tailEnd
        if (end.type == ArrowType.NONE) return null
        val tip = if (atStart) points.first() else points.last()
        val inner = if (atStart) points[1] else points[points.size - 2]
        val width = resolvedWidthEmu(line.widthEmu, devicePixelEmu)
        return arrowGeometry(end, tip, direction(tip, inner), width)
    }

    fun arrowGeometry(
        end: ArrowEnd,
        tip: ScenePoint,
        direction: ScenePoint,
        lineWidth: Double,
    ): ArrowGeometry? {
        if (end.type == ArrowType.NONE) return null
        val length = end.lengthEmu(lineWidth)
        if (length <= 0.0) return null
        val halfWidth = end.widthEmu(lineWidth) / 2.0
        val magnitude = hypot(direction.x, direction.y)
        val forward = if (magnitude <= 0.0) {
            ScenePoint(1.0, 0.0)
        } else {
            ScenePoint(direction.x / magnitude, direction.y / magnitude)
        }
        val back = ScenePoint(tip.x - forward.x * length, tip.y - forward.y * length)
        val normal = ScenePoint(-forward.y, forward.x)

        fun pointAt(base: ScenePoint, offset: Double): ScenePoint =
            ScenePoint(base.x + normal.x * offset, base.y + normal.y * offset)

        return when (end.type) {
            ArrowType.NONE -> null
            ArrowType.TRIANGLE -> ArrowGeometry(
                listOf(tip, pointAt(back, halfWidth), pointAt(back, -halfWidth)),
                closed = true,
                filled = true,
            )
            ArrowType.STEALTH -> {
                val notch = ScenePoint(
                    tip.x - forward.x * length * 0.6,
                    tip.y - forward.y * length * 0.6,
                )
                ArrowGeometry(
                    listOf(tip, pointAt(back, halfWidth), notch, pointAt(back, -halfWidth)),
                    closed = true,
                    filled = true,
                )
            }
            ArrowType.DIAMOND -> ArrowGeometry(
                listOf(
                    tip,
                    pointAt(ScenePoint(tip.x - forward.x * length / 2.0, tip.y - forward.y * length / 2.0), halfWidth),
                    back,
                    pointAt(ScenePoint(tip.x - forward.x * length / 2.0, tip.y - forward.y * length / 2.0), -halfWidth),
                ),
                closed = true,
                filled = true,
            )
            ArrowType.OVAL -> {
                val centre = ScenePoint(tip.x - forward.x * length / 2.0, tip.y - forward.y * length / 2.0)
                val radius = length / 2.0
                val points = (0 until OVAL_POINTS).map { step ->
                    val angle = 2.0 * PI * step / OVAL_POINTS
                    ScenePoint(
                        centre.x + forward.x * cos(angle) * radius + normal.x * sin(angle) * halfWidth,
                        centre.y + forward.y * cos(angle) * radius + normal.y * sin(angle) * halfWidth,
                    )
                }
                ArrowGeometry(points, closed = true, filled = true)
            }
            ArrowType.ARROW -> ArrowGeometry(
                listOf(pointAt(back, halfWidth), tip, pointAt(back, -halfWidth)),
                closed = false,
                filled = false,
            )
        }
    }

    fun trim(points: List<ScenePoint>, startEmu: Double, endEmu: Double): List<ScenePoint> {
        if (points.size < 2) return points
        var result = trimStart(points, max(0.0, startEmu))
        if (endEmu > 0.0) result = trimStart(result.asReversed(), endEmu).asReversed()
        return result
    }

    private fun trimStart(points: List<ScenePoint>, distance: Double): List<ScenePoint> {
        var remaining = distance
        for (index in 0 until points.size - 1) {
            val from = points[index]
            val to = points[index + 1]
            val length = hypot(to.x - from.x, to.y - from.y)
            if (length <= 0.0) continue
            if (remaining < length) {
                val fraction = remaining / length
                val start = ScenePoint(
                    from.x + (to.x - from.x) * fraction,
                    from.y + (to.y - from.y) * fraction,
                )
                return listOf(start) + points.subList(index + 1, points.size)
            }
            remaining -= length
        }
        return listOf(points.last())
    }

    private fun direction(tip: ScenePoint, inner: ScenePoint): ScenePoint {
        val dx = tip.x - inner.x
        val dy = tip.y - inner.y
        val length = hypot(dx, dy)
        return if (length <= 0.0) ScenePoint(1.0, 0.0) else ScenePoint(dx / length, dy / length)
    }

    private const val OVAL_POINTS = 16
}
