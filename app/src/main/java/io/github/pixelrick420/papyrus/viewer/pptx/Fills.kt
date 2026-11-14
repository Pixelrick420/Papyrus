package io.github.pixelrick420.papyrus.viewer.pptx

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** One colour stop: [offset] from 0 at the start of the fill to 1 at its end, and its resolved colour. */
internal class GradientStop(val offset: Double, val color: Rgba)

/** A `a:lin` gradient, kept in the shape's own space so the renderer can map it onto the frame. */
internal class LinearGradient(
    val angleDegrees: Double,
    /** `a:lin@scaled`, which lets the frame's aspect stretch the angle; the file's default is on. */
    val scaled: Boolean,
    val stops: List<GradientStop>,
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

/**
 * Turns a fill element into the paint a renderer applies: a resolved colour, nothing, or a gradient.
 * Pictures and patterns stay whole for the passes that own them.
 */
internal object Fills {

    fun paintOf(element: XNode, placeholder: Rgba?, colors: ColorModel, maxStops: Int): ScenePaint =
        when (element.name.localName) {
            // A group carries no fill of its own, so a group-filled shape paints nothing.
            "noFill", "grpFill" -> ScenePaint.None
            "solidFill" -> solidOf(element, placeholder, colors) ?: ScenePaint.Complex(element, placeholder)
            "gradFill" -> gradientOf(element, placeholder, colors, maxStops)
                ?: ScenePaint.Complex(element, placeholder)
            else -> ScenePaint.Complex(element, placeholder)
        }

    private fun solidOf(fill: XNode, placeholder: Rgba?, colors: ColorModel): ScenePaint? {
        val colour = fill.children.firstNotNullOfOrNull(ColorModel::valueOf) ?: return null
        val resolved = colors.resolve(colour, placeholder) ?: return null
        return ScenePaint.Solid(resolved)
    }

    private fun gradientOf(element: XNode, placeholder: Rgba?, colors: ColorModel, maxStops: Int): ScenePaint? {
        val stops = stopsOf(element, placeholder, colors, maxStops)
        if (stops.isEmpty()) return null
        element.child(OoxmlNamespace.A, "lin")?.let { line ->
            return ScenePaint.Linear(
                LinearGradient(
                    angleDegrees = line.attribute("ang")?.toDoubleOrNull()?.div(ANGLE_UNIT) ?: 0.0,
                    scaled = line.attribute("scaled").let { it == null || it == "1" || it.equals("true", true) },
                    stops = stops,
                ),
            )
        }
        val path = element.child(OoxmlNamespace.A, "path") ?: return null
        if (path.attribute("path") != "circle") return null
        return ScenePaint.Radial(radialOf(path, stops))
    }

    private fun radialOf(path: XNode, stops: List<GradientStop>): RadialGradient {
        val focus = path.child(OoxmlNamespace.A, "fillToRect")
        return RadialGradient(
            stops = stops,
            left = focusInset(focus, "l"),
            top = focusInset(focus, "t"),
            right = focusInset(focus, "r"),
            bottom = focusInset(focus, "b"),
        )
    }

    /** A missing `fillToRect` centres the focus; within a present one, the schema default for an omitted side is 0. */
    private fun focusInset(focus: XNode?, side: String): Double {
        val value = focus?.attribute(side)?.toDoubleOrNull()
            ?: return if (focus == null) RadialGradient.CENTRE_FOCUS else 0.0
        return (value / PERCENT_UNIT).coerceIn(0.0, 1.0)
    }

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
}
