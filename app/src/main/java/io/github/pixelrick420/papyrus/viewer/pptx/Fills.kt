package io.github.pixelrick420.papyrus.viewer.pptx

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** How the neighbouring tiles of a tiled gradient mirror, from `a:gradFill@flip`. */
internal enum class GradientFlip {
    NONE, X, Y, BOTH;

    companion object {
        fun of(value: String?): GradientFlip = when (value) {
            "x" -> X
            "y" -> Y
            "xy" -> BOTH
            else -> NONE
        }
    }
}

/** An `a:gradFill/a:tileRect` inset, with the fill's `flip`, both defaulting to a single whole-shape tile. */
internal class GradientTile(
    val left: Double = 0.0,
    val top: Double = 0.0,
    val right: Double = 0.0,
    val bottom: Double = 0.0,
    val flip: GradientFlip = GradientFlip.NONE,
) {
    val isEmpty: Boolean
        get() = left == 0.0 && top == 0.0 && right == 0.0 && bottom == 0.0 && flip == GradientFlip.NONE
}

/** One colour stop: [offset] from 0 at the start of the fill to 1 at its end, and its resolved colour. */
internal class GradientStop(val offset: Double, val color: Rgba)

/** A `a:lin` gradient, kept in the shape's own space so the renderer can map it onto the frame. */
internal class LinearGradient(
    val angleDegrees: Double,
    /** `a:lin@scaled`, which lets the frame's aspect stretch the angle; the file's default is on. */
    val scaled: Boolean,
    val stops: List<GradientStop>,
    val rotatesWithShape: Boolean = true,
    val tile: GradientTile = GradientTile(),
) {

    fun angleIn(frame: SceneRect?): Double {
        if (!scaled || frame == null || frame.width <= 0.0 || frame.height <= 0.0) return angleDegrees
        val radians = angleDegrees * PI / 180.0
        return atan2(sin(radians) * frame.height, cos(radians) * frame.width) * 180.0 / PI
    }
}

/**
 * A `a:path path="circle"` gradient. Its focus rectangle is kept as insets from the tile's edges, the
 * way `a:fillToRect` states them, so a renderer can map it onto the frame.
 */
internal class RadialGradient(
    val stops: List<GradientStop>,
    val left: Double = CENTRE_FOCUS,
    val top: Double = CENTRE_FOCUS,
    val right: Double = CENTRE_FOCUS,
    val bottom: Double = CENTRE_FOCUS,
    val rotatesWithShape: Boolean = true,
    val tile: GradientTile = GradientTile(),
) {

    val centerX: Double get() = (1.0 + left - right) / 2.0

    val centerY: Double get() = (1.0 + top - bottom) / 2.0

    val innerRadiusX: Double get() = ((1.0 - left - right) / 2.0).coerceAtLeast(0.0)

    val innerRadiusY: Double get() = ((1.0 - top - bottom) / 2.0).coerceAtLeast(0.0)

    companion object {
        /** The centred focus PowerPoint writes for its centre preset, used when `a:fillToRect` is absent. */
        const val CENTRE_FOCUS = 0.5
    }
}

/** A `a:path path="rect"` or `shape` gradient: stops across the rectangle left by [fillToRect]'s insets. */
internal class PathGradient(
    val stops: List<GradientStop>,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val followsShape: Boolean,
    val rotatesWithShape: Boolean = true,
    val tile: GradientTile = GradientTile(),
)

/**
 * Turns a fill element into the paint a renderer applies: a resolved colour, nothing, a pattern or a gradient.
 * Picture fills stay whole for the pass that owns them.
 */
internal object Fills {

    fun paintOf(element: XNode, placeholder: Rgba?, colors: ColorModel, maxStops: Int): ScenePaint =
        when (element.name.localName) {
            // A group carries no fill of its own, so a group-filled shape paints nothing.
            "noFill", "grpFill" -> ScenePaint.None
            "solidFill" -> solidOf(element, placeholder, colors) ?: ScenePaint.Complex(element, placeholder)
            "gradFill" -> gradientOf(element, placeholder, colors, maxStops)
                ?: ScenePaint.Complex(element, placeholder)
            "pattFill" -> patternOf(element, placeholder, colors) ?: ScenePaint.Complex(element, placeholder)
            else -> ScenePaint.Complex(element, placeholder)
        }

    private fun solidOf(fill: XNode, placeholder: Rgba?, colors: ColorModel): ScenePaint? {
        val colour = fill.children.firstNotNullOfOrNull(ColorModel::valueOf) ?: return null
        val resolved = colors.resolve(colour, placeholder) ?: return null
        return ScenePaint.Solid(resolved)
    }

    private fun patternOf(fill: XNode, placeholder: Rgba?, colors: ColorModel): ScenePaint? {
        val tile = PatternFills.tileOf(fill.attribute("prst")) ?: return null
        val foreground = resolvedColorOf(fill.child(OoxmlNamespace.A, "fgClr"), placeholder, colors) ?: return null
        val background = resolvedColorOf(fill.child(OoxmlNamespace.A, "bgClr"), placeholder, colors)
        val scale = fill.child(OoxmlNamespace.A, "scale")?.attribute("w")?.toDoubleOrNull()
            ?.div(PERCENT_UNIT)?.coerceIn(TILE_SCALE_MIN, TILE_SCALE_MAX) ?: 1.0
        return ScenePaint.Pattern(tile, foreground, background, scale, rotatesWithShapeOf(fill))
    }

    private fun resolvedColorOf(node: XNode?, placeholder: Rgba?, colors: ColorModel): Rgba? =
        node?.children?.firstNotNullOfOrNull(ColorModel::valueOf)?.let { colors.resolve(it, placeholder) }

    private fun gradientOf(element: XNode, placeholder: Rgba?, colors: ColorModel, maxStops: Int): ScenePaint? {
        val stops = stopsOf(element, placeholder, colors, maxStops)
        if (stops.isEmpty()) return null
        val rotates = rotatesWithShapeOf(element)
        val tile = tileOf(element)
        element.child(OoxmlNamespace.A, "lin")?.let { line ->
            return ScenePaint.Linear(
                LinearGradient(
                    angleDegrees = line.attribute("ang")?.toDoubleOrNull()?.div(ANGLE_UNIT) ?: 0.0,
                    scaled = line.attribute("scaled").let { it == null || it == "1" || it.equals("true", true) },
                    stops = stops,
                    rotatesWithShape = rotates,
                    tile = tile,
                ),
            )
        }
        val path = element.child(OoxmlNamespace.A, "path") ?: return null
        val focus = path.child(OoxmlNamespace.A, "fillToRect")
        return when (path.attribute("path")) {
            "circle" -> ScenePaint.Radial(radialOf(focus, stops, rotates, tile))
            "rect" -> ScenePaint.Path(pathOf(focus, stops, followsShape = false, rotates = rotates, tile = tile))
            "shape" -> ScenePaint.Path(pathOf(focus, stops, followsShape = true, rotates = rotates, tile = tile))
            else -> null
        }
    }

    private fun radialOf(focus: XNode?, stops: List<GradientStop>, rotates: Boolean, tile: GradientTile): RadialGradient {
        val missing = if (focus == null) RadialGradient.CENTRE_FOCUS else 0.0
        return RadialGradient(
            stops = stops,
            left = focusInset(focus, "l", missing),
            top = focusInset(focus, "t", missing),
            right = focusInset(focus, "r", missing),
            bottom = focusInset(focus, "b", missing),
            rotatesWithShape = rotates,
            tile = tile,
        )
    }

    private fun pathOf(
        focus: XNode?,
        stops: List<GradientStop>,
        followsShape: Boolean,
        rotates: Boolean,
        tile: GradientTile,
    ): PathGradient = PathGradient(
        stops = stops,
        left = focusInset(focus, "l", 0.0),
        top = focusInset(focus, "t", 0.0),
        right = focusInset(focus, "r", 0.0),
        bottom = focusInset(focus, "b", 0.0),
        followsShape = followsShape,
        rotatesWithShape = rotates,
        tile = tile,
    )

    private fun tileOf(element: XNode): GradientTile {
        val rect = element.child(OoxmlNamespace.A, "tileRect") ?: return GradientTile(flip = GradientFlip.of(element.attribute("flip")))
        return GradientTile(
            left = tileInset(rect, "l"),
            top = tileInset(rect, "t"),
            right = tileInset(rect, "r"),
            bottom = tileInset(rect, "b"),
            flip = GradientFlip.of(element.attribute("flip")),
        )
    }

    private fun tileInset(rect: XNode, side: String): Double {
        val value = rect.attribute(side)?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return 0.0
        return (value / PERCENT_UNIT).coerceIn(-1.0, 1.0)
    }

    /** A missing side centres a circle's focus; a path gradient's absent rectangle covers the whole frame. */
    private fun focusInset(focus: XNode?, side: String, missing: Double): Double {
        val value = focus?.attribute(side)?.toDoubleOrNull() ?: return missing
        return (value / PERCENT_UNIT).coerceIn(0.0, 1.0)
    }

    private fun rotatesWithShapeOf(element: XNode): Boolean =
        element.attribute("rotWithShape").let { it == null || it == "1" || it.equals("true", true) }

    private fun stopsOf(element: XNode, placeholder: Rgba?, colors: ColorModel, maxStops: Int): List<GradientStop> {
        val list = element.child(OoxmlNamespace.A, "gsLst") ?: return emptyList()
        return list.childrenOf(OoxmlNamespace.A, "gs").take(maxStops.coerceAtLeast(0)).mapNotNull { stop ->
            val colour = stop.children.firstNotNullOfOrNull(ColorModel::valueOf) ?: return@mapNotNull null
            val resolved = colors.resolve(colour, placeholder) ?: return@mapNotNull null
            GradientStop(stopOffset(stop), resolved)
        }.sortedBy { it.offset }
    }

    private fun stopOffset(stop: XNode): Double =
        ((stop.attribute("pos")?.toDoubleOrNull() ?: 0.0) / PERCENT_UNIT).coerceIn(0.0, 1.0)

    private const val ANGLE_UNIT = 60_000.0
    private const val PERCENT_UNIT = 100_000.0
    private const val TILE_SCALE_MIN = 0.001
    private const val TILE_SCALE_MAX = 100.0
}
