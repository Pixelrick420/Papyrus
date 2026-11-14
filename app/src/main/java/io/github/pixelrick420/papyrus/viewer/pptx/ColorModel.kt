package io.github.pixelrick420.papyrus.viewer.pptx

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** An sRGB colour, each channel and the alpha 0..255. */
internal data class Rgba(val red: Int, val green: Int, val blue: Int, val alpha: Int = 255) {

    /** Packed as 0xAARRGGBB. */
    val argb: Int get() = (alpha shl 24) or (red shl 16) or (green shl 8) or blue

    companion object {
        val BLACK = Rgba(0, 0, 0)
        val WHITE = Rgba(255, 255, 255)

        fun of(hex: Int): Rgba = Rgba((hex shr 16) and 0xFF, (hex shr 8) and 0xFF, hex and 0xFF)

        /** `RRGGBB` or `AARRGGBB` as the file writes it, or null when it is neither. */
        fun parse(hex: String): Rgba? {
            val digits = hex.trim()
            if (!HEX.matches(digits)) return null
            val value = digits.toLong(16)
            val alpha = if (digits.length == 8) ((value shr 24) and 0xFF).toInt() else 255
            return Rgba(
                ((value shr 16) and 0xFF).toInt(),
                ((value shr 8) and 0xFF).toInt(),
                (value and 0xFF).toInt(),
                alpha,
            )
        }

        private val HEX = Regex("[0-9A-Fa-f]{6}|[0-9A-Fa-f]{8}")
    }
}

internal sealed interface ColorSource {
    class Srgb(val color: Rgba) : ColorSource
    class Scheme(val slot: String) : ColorSource
    class System(val name: String, val last: Rgba?) : ColorSource
    class Preset(val name: String) : ColorSource
    class ScRgb(val red: Int, val green: Int, val blue: Int) : ColorSource
    class Hsl(val hue: Int, val saturation: Int, val luminance: Int) : ColorSource
}

internal enum class ColorTransformKind {
    ALPHA, ALPHA_MOD, ALPHA_OFF,
    HUE, HUE_MOD, HUE_OFF,
    SAT, SAT_MOD, SAT_OFF,
    LUM, LUM_MOD, LUM_OFF,
    RED, RED_MOD, RED_OFF,
    GREEN, GREEN_MOD, GREEN_OFF,
    BLUE, BLUE_MOD, BLUE_OFF,
    TINT, SHADE, COMP, INV, GRAY, GAMMA, INV_GAMMA,
}

/** One `a:*` transform child of a colour element; [value] is the file's own unit for [kind]. */
internal class ColorTransform(val kind: ColorTransformKind, val value: Int)

/** A colour as written in a part: a source, then the transforms applied to it in document order. */
internal class ColorValue(val source: ColorSource, val transforms: List<ColorTransform> = emptyList())

/** The theme's `a:clrScheme`, keyed by slot name. */
internal class ThemeColorScheme(private val slots: Map<String, ColorValue>) {

    operator fun get(slot: String): ColorValue? = slots[slot]

    val names: Set<String> get() = slots.keys
}

/**
 * `clrMap` or `clrMapOvr`: which theme slot each of the twelve colour-map names points at.
 * A name the map does not re-point falls back to the schema's own Office mapping.
 */
internal class ColorMap(private val slots: Map<String, String> = DEFAULT) {

    fun target(slot: String): String = slots[slot] ?: slot

    companion object {
        val SLOTS: List<String> = listOf(
            "bg1", "tx1", "bg2", "tx2",
            "accent1", "accent2", "accent3", "accent4", "accent5", "accent6",
            "hlink", "folHlink",
        )

        private val DEFAULT = mapOf("bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2")

        fun from(node: XNode?): ColorMap {
            if (node == null) return ColorMap()
            val overrides = SLOTS.mapNotNull { slot -> node.attribute(slot)?.let { slot to it } }.toMap()
            return ColorMap(DEFAULT + overrides)
        }
    }
}

/**
 * Resolves a colour element to the sRGB value PowerPoint shows. Transforms apply in document order,
 * and each works in the space it is defined in: HSL for hue/saturation/luminance, linear RGB for
 * tint, shade and gamma, per channel in sRGB for red, green and blue.
 */
internal class ColorModel(
    private val scheme: ThemeColorScheme? = null,
    private val colorMap: ColorMap = ColorMap(),
) {

    fun resolve(node: XNode?, placeholder: Rgba? = null): Rgba? =
        node?.let(::valueOf)?.let { resolve(it, placeholder) }

    fun resolve(value: ColorValue, placeholder: Rgba? = null): Rgba? =
        chainOf(value, placeholder, 0)?.let(::applyTransforms)

    private fun chainOf(value: ColorValue, placeholder: Rgba?, depth: Int): Chain? {
        if (depth > MAX_DEPTH) return null
        val inherited = when (val source = value.source) {
            is ColorSource.Scheme -> when (source.slot) {
                "phClr" -> placeholder?.let { Chain(it, emptyList()) }
                else -> scheme?.get(colorMap.target(source.slot))?.let { chainOf(it, placeholder, depth + 1) }
            }
            else -> baseOf(source)?.let { Chain(it, emptyList()) }
        }
        return inherited?.let { Chain(it.base, it.transforms + value.transforms) }
    }

    private fun baseOf(source: ColorSource): Rgba? = when (source) {
        is ColorSource.Srgb -> source.color
        is ColorSource.System -> source.last
        is ColorSource.Preset -> PresetColors.of(source.name)
        is ColorSource.ScRgb -> Rgba(
            fromLinear(source.red / 100_000.0),
            fromLinear(source.green / 100_000.0),
            fromLinear(source.blue / 100_000.0),
        )
        is ColorSource.Hsl -> Hsl(
            source.hue / 60_000.0,
            source.saturation / 100_000.0,
            source.luminance / 100_000.0,
        ).toRgba()
        is ColorSource.Scheme -> null
    }

    private fun applyTransforms(chain: Chain): Rgba {
        val working = Working(
            chain.base.red / 255.0,
            chain.base.green / 255.0,
            chain.base.blue / 255.0,
            chain.base.alpha / 255.0,
        )
        for (transform in chain.transforms) applyTransform(transform, working)
        return working.toRgba()
    }

    private fun applyTransform(transform: ColorTransform, working: Working) {
        val percent = transform.value / 100_000.0
        val degrees = transform.value / 60_000.0
        when (transform.kind) {
            ColorTransformKind.ALPHA -> working.alpha = percent
            ColorTransformKind.ALPHA_MOD -> working.alpha *= percent
            ColorTransformKind.ALPHA_OFF -> working.alpha += percent
            ColorTransformKind.HUE -> working.inHsl { it.copy(hue = degrees) }
            ColorTransformKind.HUE_MOD -> working.inHsl { it.copy(hue = it.hue * percent) }
            ColorTransformKind.HUE_OFF -> working.inHsl { it.copy(hue = it.hue + degrees) }
            ColorTransformKind.SAT -> working.inHsl { it.copy(saturation = percent) }
            ColorTransformKind.SAT_MOD -> working.inHsl { it.copy(saturation = it.saturation * percent) }
            ColorTransformKind.SAT_OFF -> working.inHsl { it.copy(saturation = it.saturation + percent) }
            ColorTransformKind.LUM -> working.inHsl { it.copy(luminance = percent) }
            ColorTransformKind.LUM_MOD -> working.inHsl { it.copy(luminance = it.luminance * percent) }
            ColorTransformKind.LUM_OFF -> working.inHsl { it.copy(luminance = it.luminance + percent) }
            ColorTransformKind.RED -> working.redEach { percent }
            ColorTransformKind.RED_MOD -> working.redEach { it * percent }
            ColorTransformKind.RED_OFF -> working.redEach { it + percent }
            ColorTransformKind.GREEN -> working.greenEach { percent }
            ColorTransformKind.GREEN_MOD -> working.greenEach { it * percent }
            ColorTransformKind.GREEN_OFF -> working.greenEach { it + percent }
            ColorTransformKind.BLUE -> working.blueEach { percent }
            ColorTransformKind.BLUE_MOD -> working.blueEach { it * percent }
            ColorTransformKind.BLUE_OFF -> working.blueEach { it + percent }
            ColorTransformKind.TINT -> working.linearEach { it * percent + (1 - percent) }
            ColorTransformKind.SHADE -> working.linearEach { it * percent }
            ColorTransformKind.COMP -> working.inHsl { it.copy(hue = it.hue + 180) }
            ColorTransformKind.INV -> working.srgbEach { 1 - it }
            ColorTransformKind.GRAY -> working.inHsl { it.copy(saturation = 0.0) }
            ColorTransformKind.GAMMA -> working.srgbEach(::linearOf)
            ColorTransformKind.INV_GAMMA -> working.srgbEach(::srgbOf)
        }
    }

    companion object {
        private const val MAX_DEPTH = 8

        fun valueOf(node: XNode): ColorValue? {
            val source = sourceOf(node) ?: return null
            return ColorValue(source, transformsOf(node))
        }

        fun sourceOf(node: XNode): ColorSource? {
            if (node.name.namespace != OoxmlNamespace.A) return null
            return when (node.name.localName) {
                "srgbClr" -> node.attribute("val")?.let(Rgba::parse)?.let { ColorSource.Srgb(it) }
                "schemeClr" -> node.attribute("val")?.let { ColorSource.Scheme(it) }
                "sysClr" -> node.attribute("val")?.let {
                    ColorSource.System(it, node.attribute("lastClr")?.let(Rgba::parse))
                }
                "prstClr" -> node.attribute("val")?.let { ColorSource.Preset(it) }
                "scrgbClr" -> ColorSource.ScRgb(channel(node, "r"), channel(node, "g"), channel(node, "b"))
                "hslClr" -> ColorSource.Hsl(channel(node, "hue"), channel(node, "sat"), channel(node, "lum"))
                else -> null
            }
        }

        fun transformsOf(node: XNode): List<ColorTransform> = node.children.mapNotNull { child ->
            if (child.name.namespace != OoxmlNamespace.A) return@mapNotNull null
            val kind = KIND_BY_NAME[child.name.localName] ?: return@mapNotNull null
            val value = child.attribute("val")?.toIntOrNull()
            if (value == null && kind !in VALUELESS) return@mapNotNull null
            ColorTransform(kind, value ?: 0)
        }

        private val VALUELESS: Set<ColorTransformKind> = setOf(
            ColorTransformKind.COMP,
            ColorTransformKind.INV,
            ColorTransformKind.GRAY,
            ColorTransformKind.GAMMA,
            ColorTransformKind.INV_GAMMA,
        )

        private fun channel(node: XNode, name: String): Int = node.attribute(name)?.toIntOrNull() ?: 0

        private val KIND_BY_NAME: Map<String, ColorTransformKind> = mapOf(
            "alpha" to ColorTransformKind.ALPHA,
            "alphaMod" to ColorTransformKind.ALPHA_MOD,
            "alphaOff" to ColorTransformKind.ALPHA_OFF,
            "hue" to ColorTransformKind.HUE,
            "hueMod" to ColorTransformKind.HUE_MOD,
            "hueOff" to ColorTransformKind.HUE_OFF,
            "sat" to ColorTransformKind.SAT,
            "satMod" to ColorTransformKind.SAT_MOD,
            "satOff" to ColorTransformKind.SAT_OFF,
            "lum" to ColorTransformKind.LUM,
            "lumMod" to ColorTransformKind.LUM_MOD,
            "lumOff" to ColorTransformKind.LUM_OFF,
            "red" to ColorTransformKind.RED,
            "redMod" to ColorTransformKind.RED_MOD,
            "redOff" to ColorTransformKind.RED_OFF,
            "green" to ColorTransformKind.GREEN,
            "greenMod" to ColorTransformKind.GREEN_MOD,
            "greenOff" to ColorTransformKind.GREEN_OFF,
            "blue" to ColorTransformKind.BLUE,
            "blueMod" to ColorTransformKind.BLUE_MOD,
            "blueOff" to ColorTransformKind.BLUE_OFF,
            "tint" to ColorTransformKind.TINT,
            "shade" to ColorTransformKind.SHADE,
            "comp" to ColorTransformKind.COMP,
            "inv" to ColorTransformKind.INV,
            "gray" to ColorTransformKind.GRAY,
            "gamma" to ColorTransformKind.GAMMA,
            "invGamma" to ColorTransformKind.INV_GAMMA,
        )
    }
}

private class Chain(val base: Rgba, val transforms: List<ColorTransform>)

/** A colour held as sRGB fractions; each transform converts into the space it is defined in and back. */
private class Working(
    var red: Double,
    var green: Double,
    var blue: Double,
    var alpha: Double,
) {

    fun inHsl(block: (Hsl) -> Hsl) {
        val rgb = block(Hsl.of(red, green, blue)).toRgba()
        red = rgb.red / 255.0
        green = rgb.green / 255.0
        blue = rgb.blue / 255.0
    }

    /** Applies [block] in linear RGB, then encodes the result back into sRGB. */
    fun linearEach(block: (Double) -> Double) {
        red = srgbOf(block(linearOf(red)))
        green = srgbOf(block(linearOf(green)))
        blue = srgbOf(block(linearOf(blue)))
    }

    fun srgbEach(block: (Double) -> Double) {
        red = block(red)
        green = block(green)
        blue = block(blue)
    }

    fun redEach(block: (Double) -> Double) {
        red = block(red)
    }

    fun greenEach(block: (Double) -> Double) {
        green = block(green)
    }

    fun blueEach(block: (Double) -> Double) {
        blue = block(blue)
    }

    fun toRgba(): Rgba = Rgba(channel(red), channel(green), channel(blue), channel(alpha))

    private fun channel(value: Double): Int = (value.coerceIn(0.0, 1.0) * 255).roundToInt()
}

/** Hue in degrees 0..360, saturation and luminance 0..1. */
private data class Hsl(val hue: Double, val saturation: Double, val luminance: Double) {

    fun toRgba(): Rgba {
        val h = normalise(hue)
        val s = saturation.coerceIn(0.0, 1.0)
        val l = luminance.coerceIn(0.0, 1.0)
        if (s == 0.0) {
            val grey = (l * 255).roundToInt()
            return Rgba(grey, grey, grey)
        }
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        return Rgba(
            (hueToChannel(p, q, h / 360 + 1.0 / 3) * 255).roundToInt(),
            (hueToChannel(p, q, h / 360) * 255).roundToInt(),
            (hueToChannel(p, q, h / 360 - 1.0 / 3) * 255).roundToInt(),
        )
    }

    companion object {
        fun of(red: Double, green: Double, blue: Double): Hsl {
            val maximum = max(red, max(green, blue))
            val minimum = min(red, min(green, blue))
            val luminance = (maximum + minimum) / 2
            if (maximum == minimum) return Hsl(0.0, 0.0, luminance)
            val delta = maximum - minimum
            val saturation = if (luminance > 0.5) {
                delta / (2 - maximum - minimum)
            } else {
                delta / (maximum + minimum)
            }
            val hue = when (maximum) {
                red -> (green - blue) / delta + if (green < blue) 6 else 0
                green -> (blue - red) / delta + 2
                else -> (red - green) / delta + 4
            } * 60
            return Hsl(hue, saturation, luminance)
        }

        private fun normalise(hue: Double): Double = ((hue % 360) + 360) % 360

        private fun hueToChannel(p: Double, q: Double, t: Double): Double {
            val channel = when {
                t < 0 -> t + 1
                t > 1 -> t - 1
                else -> t
            }
            return when {
                channel < 1.0 / 6 -> p + (q - p) * 6 * channel
                channel < 0.5 -> q
                channel < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - channel) * 6
                else -> p
            }
        }
    }
}

private fun linearOf(value: Double): Double {
    val c = value.coerceIn(0.0, 1.0)
    return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
}

private fun srgbOf(value: Double): Double {
    val c = value.coerceIn(0.0, 1.0)
    val encoded = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055
    return encoded.coerceIn(0.0, 1.0)
}

private fun fromLinear(value: Double): Int = (srgbOf(value) * 255).roundToInt()

private object PresetColors {

    fun of(name: String): Rgba? = NAMES[name.lowercase()]?.let(Rgba::of)

    private val NAMES: Map<String, Int> = mapOf(
        "aliceblue" to 0xF0F8FF,
        "antiquewhite" to 0xFAEBD7,
        "aqua" to 0x00FFFF,
        "aquamarine" to 0x7FFFD4,
        "azure" to 0xF0FFFF,
        "beige" to 0xF5F5DC,
        "bisque" to 0xFFE4C4,
        "black" to 0x000000,
        "blanchedalmond" to 0xFFEBCD,
        "blue" to 0x0000FF,
        "blueviolet" to 0x8A2BE2,
        "brown" to 0xA52A2A,
        "burlywood" to 0xDEB887,
        "cadetblue" to 0x5F9EA0,
        "chartreuse" to 0x7FFF00,
        "chocolate" to 0xD2691E,
        "coral" to 0xFF7F50,
        "cornflowerblue" to 0x6495ED,
        "cornsilk" to 0xFFF8DC,
        "crimson" to 0xDC143C,
        "cyan" to 0x00FFFF,
        "darkblue" to 0x00008B,
        "darkcyan" to 0x008B8B,
        "darkgoldenrod" to 0xB8860B,
        "darkgray" to 0xA9A9A9,
        "darkgreen" to 0x006400,
        "darkgrey" to 0xA9A9A9,
        "darkkhaki" to 0xBDB76B,
        "darkmagenta" to 0x8B008B,
        "darkolivegreen" to 0x556B2F,
        "darkorange" to 0xFF8C00,
        "darkorchid" to 0x9932CC,
        "darkred" to 0x8B0000,
        "darksalmon" to 0xE9967A,
        "darkseagreen" to 0x8FBC8F,
        "darkslateblue" to 0x483D8B,
        "darkslategray" to 0x2F4F4F,
        "darkslategrey" to 0x2F4F4F,
        "darkturquoise" to 0x00CED1,
        "darkviolet" to 0x9400D3,
        "deeppink" to 0xFF1493,
        "deepskyblue" to 0x00BFFF,
        "dimgray" to 0x696969,
        "dimgrey" to 0x696969,
        "dkblue" to 0x00008B,
        "dkcyan" to 0x008B8B,
        "dkgoldenrod" to 0xB8860B,
        "dkgray" to 0xA9A9A9,
        "dkgreen" to 0x006400,
        "dkgrey" to 0xA9A9A9,
        "dkkhaki" to 0xBDB76B,
        "dkmagenta" to 0x8B008B,
        "dkolivegreen" to 0x556B2F,
        "dkorange" to 0xFF8C00,
        "dkorchid" to 0x9932CC,
        "dkred" to 0x8B0000,
        "dksalmon" to 0xE9967A,
        "dkseagreen" to 0x8FBC8F,
        "dkslateblue" to 0x483D8B,
        "dkslategray" to 0x2F4F4F,
        "dkslategrey" to 0x2F4F4F,
        "dkturquoise" to 0x00CED1,
        "dkviolet" to 0x9400D3,
        "dodgerblue" to 0x1E90FF,
        "firebrick" to 0xB22222,
        "floralwhite" to 0xFFFAF0,
        "forestgreen" to 0x228B22,
        "fuchsia" to 0xFF00FF,
        "gainsboro" to 0xDCDCDC,
        "ghostwhite" to 0xF8F8FF,
        "gold" to 0xFFD700,
        "goldenrod" to 0xDAA520,
        "gray" to 0x808080,
        "green" to 0x008000,
        "greenyellow" to 0xADFF2F,
        "grey" to 0x808080,
        "honeydew" to 0xF0FFF0,
        "hotpink" to 0xFF69B4,
        "indianred" to 0xCD5C5C,
        "indigo" to 0x4B0082,
        "ivory" to 0xFFFFF0,
        "khaki" to 0xF0E68C,
        "lavender" to 0xE6E6FA,
        "lavenderblush" to 0xFFF0F5,
        "lawngreen" to 0x7CFC00,
        "lemonchiffon" to 0xFFFACD,
        "lightblue" to 0xADD8E6,
        "lightcoral" to 0xF08080,
        "lightcyan" to 0xE0FFFF,
        "lightgoldenrodyellow" to 0xFAFAD2,
        "lightgray" to 0xD3D3D3,
        "lightgreen" to 0x90EE90,
        "lightgrey" to 0xD3D3D3,
        "lightpink" to 0xFFB6C1,
        "lightsalmon" to 0xFFA07A,
        "lightseagreen" to 0x20B2AA,
        "lightskyblue" to 0x87CEFA,
        "lightslategray" to 0x778899,
        "lightslategrey" to 0x778899,
        "lightsteelblue" to 0xB0C4DE,
        "lightyellow" to 0xFFFFE0,
        "lime" to 0x00FF00,
        "limegreen" to 0x32CD32,
        "linen" to 0xFAF0E6,
        "ltblue" to 0xADD8E6,
        "ltcoral" to 0xF08080,
        "ltcyan" to 0xE0FFFF,
        "ltgoldenrodyellow" to 0xFAFAD2,
        "ltgray" to 0xD3D3D3,
        "ltgreen" to 0x90EE90,
        "ltgrey" to 0xD3D3D3,
        "ltpink" to 0xFFB6C1,
        "ltsalmon" to 0xFFA07A,
        "ltseagreen" to 0x20B2AA,
        "ltskyblue" to 0x87CEFA,
        "ltslategray" to 0x778899,
        "ltslategrey" to 0x778899,
        "ltsteelblue" to 0xB0C4DE,
        "ltyellow" to 0xFFFFE0,
        "magenta" to 0xFF00FF,
        "maroon" to 0x800000,
        "medaquamarine" to 0x66CDAA,
        "medblue" to 0x0000CD,
        "medorchid" to 0xBA55D3,
        "medpurple" to 0x9370DB,
        "medseagreen" to 0x3CB371,
        "medslateblue" to 0x7B68EE,
        "medspringgreen" to 0x00FA9A,
        "medturquoise" to 0x48D1CC,
        "medvioletred" to 0xC71585,
        "midnightblue" to 0x191970,
        "mintcream" to 0xF5FFFA,
        "mistyrose" to 0xFFE4E1,
        "moccasin" to 0xFFE4B5,
        "navajowhite" to 0xFFDEAD,
        "navy" to 0x000080,
        "oldlace" to 0xFDF5E6,
        "olive" to 0x808000,
        "olivedrab" to 0x6B8E23,
        "orange" to 0xFFA500,
        "orangered" to 0xFF4500,
        "orchid" to 0xDA70D6,
        "palegoldenrod" to 0xEEE8AA,
        "palegreen" to 0x98FB98,
        "paleturquoise" to 0xAFEEEE,
        "palevioletred" to 0xDB7093,
        "papayawhip" to 0xFFEFD5,
        "peachpuff" to 0xFFDAB9,
        "peru" to 0xCD853F,
        "pink" to 0xFFC0CB,
        "plum" to 0xDDA0DD,
        "powderblue" to 0xB0E0E6,
        "purple" to 0x800080,
        "red" to 0xFF0000,
        "rosybrown" to 0xBC8F8F,
        "royalblue" to 0x4169E1,
        "saddlebrown" to 0x8B4513,
        "salmon" to 0xFA8072,
        "sandybrown" to 0xF4A460,
        "seagreen" to 0x2E8B57,
        "seashell" to 0xFFF5EE,
        "sienna" to 0xA0522D,
        "silver" to 0xC0C0C0,
        "skyblue" to 0x87CEEB,
        "slateblue" to 0x6A5ACD,
        "slategray" to 0x708090,
        "slategrey" to 0x708090,
        "snow" to 0xFFFAFA,
        "springgreen" to 0x00FF7F,
        "steelblue" to 0x4682B4,
        "tan" to 0xD2B48C,
        "teal" to 0x008080,
        "thistle" to 0xD8BFD8,
        "tomato" to 0xFF6347,
        "turquoise" to 0x40E0D0,
        "violet" to 0xEE82EE,
        "violetred" to 0xD02090,
        "wheat" to 0xF5DEB3,
        "white" to 0xFFFFFF,
        "whitesmoke" to 0xF5F5F5,
        "yellow" to 0xFFFF00,
        "yellowgreen" to 0x9ACD32,
    )
}
