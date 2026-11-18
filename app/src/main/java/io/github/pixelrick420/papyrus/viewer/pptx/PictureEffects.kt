package io.github.pixelrick420.papyrus.viewer.pptx

import kotlin.math.abs
import kotlin.math.roundToInt

internal data class DuotoneEffect(val dark: Rgba, val light: Rgba)

internal data class ColorChangeEffect(val from: Rgba, val to: Rgba)

internal data class TintEffect(val color: Rgba, val amount: Double)

internal data class HslEffect(val hueDegrees: Double, val saturation: Double, val luminance: Double)

private const val LUMA_RED = 0.299
private const val LUMA_GREEN = 0.587
private const val LUMA_BLUE = 0.114
private const val COLOR_TOLERANCE = 16

/** The blip-level recolour effects, carried from the parser to the raster backend. */
internal data class PictureEffects(
    val matrix: List<Float>? = null,
    val duotone: DuotoneEffect? = null,
    val colorChange: ColorChangeEffect? = null,
    val tint: TintEffect? = null,
    val hsl: HslEffect? = null,
) {

    val hasPixelWork: Boolean get() = duotone != null || colorChange != null || tint != null || hsl != null

    val isEmpty: Boolean get() = matrix == null && !hasPixelWork

    fun applyTo(color: Rgba): Rgba {
        var result = matrix?.let { applyMatrix(it, color) } ?: color
        hsl?.let { result = it.applyTo(result) }
        duotone?.let { result = it.applyTo(result) }
        colorChange?.let { result = it.applyTo(result) }
        tint?.let { result = it.applyTo(result) }
        return result
    }

    companion object {

        val EMPTY = PictureEffects()

        private const val PERCENT_UNIT = 100_000.0
        private const val ANGLE_UNIT = 60_000.0
        private const val BI_LEVEL_FACTOR = 1_024f

        private val colors = ColorModel()

        fun of(blip: XNode?): PictureEffects {
            var matrix: List<Float>? = null
            var duotone: DuotoneEffect? = null
            var colorChange: ColorChangeEffect? = null
            var tint: TintEffect? = null
            var hsl: HslEffect? = null
            for (child in blip?.children.orEmpty()) {
                if (child.name.namespace != OoxmlNamespace.A) continue
                when (child.name.localName) {
                    "lum" -> {
                        val bright = percent(child.attribute("bright"))
                        val contrast = percent(child.attribute("contrast"))
                        if (bright != 0.0 || contrast != 0.0) {
                            val step = luminance(bright, contrast)
                            matrix = if (matrix == null) step else concat(step, matrix)
                        }
                    }
                    "biLevel" -> {
                        val step = biLevel(percent(child.attribute("thresh")))
                        matrix = if (matrix == null) step else concat(step, matrix)
                    }
                    "grayscl" -> {
                        val step = grayscale()
                        matrix = if (matrix == null) step else concat(step, matrix)
                    }
                    "duotone" -> {
                        val endpoints = child.children.mapNotNull(ColorModel::valueOf).mapNotNull(colors::resolve)
                        if (endpoints.size >= 2) duotone = DuotoneEffect(endpoints[0], endpoints[1])
                    }
                    "clrChange" -> {
                        val from = colorOf(child, "clrFrom")
                        val to = colorOf(child, "clrTo")
                        if (from != null && to != null) colorChange = ColorChangeEffect(from, to)
                    }
                    "tint" -> {
                        val color = child.children.firstNotNullOfOrNull(ColorModel::valueOf)?.let(colors::resolve)
                        val amount = percent(child.attribute("amt"))
                        if (color != null && amount != 0.0) tint = TintEffect(color, amount)
                    }
                    "hsl" -> {
                        val effect = HslEffect(
                            hueDegrees = (child.attribute("hue")?.toDoubleOrNull() ?: 0.0) / ANGLE_UNIT,
                            saturation = percent(child.attribute("sat")),
                            luminance = percent(child.attribute("lum")),
                        )
                        if (effect.hueDegrees != 0.0 || effect.saturation != 0.0 || effect.luminance != 0.0) hsl = effect
                    }
                }
            }
            return PictureEffects(matrix, duotone, colorChange, tint, hsl)
        }

        private fun colorOf(node: XNode, tag: String): Rgba? =
            node.child(OoxmlNamespace.A, tag)?.children?.firstNotNullOfOrNull(ColorModel::valueOf)?.let(colors::resolve)

        private fun luminance(bright: Double, contrast: Double): List<Float> {
            val slope = (1.0 + contrast).toFloat()
            val offset = (127.5 - 127.5 * (1.0 + contrast) + bright * 255.0).toFloat()
            return listOf(
                slope, 0f, 0f, 0f, offset,
                0f, slope, 0f, 0f, offset,
                0f, 0f, slope, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            )
        }

        private fun biLevel(threshold: Double): List<Float> {
            val level = (threshold * 255.0).toFloat()
            val offset = 127.5f - BI_LEVEL_FACTOR * level
            return listOf(
                LUMA_RED.toFloat() * BI_LEVEL_FACTOR, LUMA_GREEN.toFloat() * BI_LEVEL_FACTOR, LUMA_BLUE.toFloat() * BI_LEVEL_FACTOR, 0f, offset,
                LUMA_RED.toFloat() * BI_LEVEL_FACTOR, LUMA_GREEN.toFloat() * BI_LEVEL_FACTOR, LUMA_BLUE.toFloat() * BI_LEVEL_FACTOR, 0f, offset,
                LUMA_RED.toFloat() * BI_LEVEL_FACTOR, LUMA_GREEN.toFloat() * BI_LEVEL_FACTOR, LUMA_BLUE.toFloat() * BI_LEVEL_FACTOR, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            )
        }

        private fun grayscale(): List<Float> = listOf(
            LUMA_RED.toFloat(), LUMA_GREEN.toFloat(), LUMA_BLUE.toFloat(), 0f, 0f,
            LUMA_RED.toFloat(), LUMA_GREEN.toFloat(), LUMA_BLUE.toFloat(), 0f, 0f,
            LUMA_RED.toFloat(), LUMA_GREEN.toFloat(), LUMA_BLUE.toFloat(), 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )

        /** Applies [inner] first, then [outer], as Android's `ColorMatrix.setConcat` does. */
        private fun concat(outer: List<Float>, inner: List<Float>): List<Float> {
            val result = FloatArray(20)
            for (row in 0..3) {
                for (column in 0..4) {
                    var sum = if (column == 4) outer[row * 5 + 4] else 0f
                    for (index in 0..3) {
                        sum += outer[row * 5 + index] * inner[index * 5 + column]
                    }
                    result[row * 5 + column] = sum
                }
            }
            result[18] = 1f
            return result.toList()
        }

        private fun applyMatrix(matrix: List<Float>, color: Rgba): Rgba {
            val red = color.red.toFloat()
            val green = color.green.toFloat()
            val blue = color.blue.toFloat()
            val alpha = color.alpha.toFloat()
            fun row(offset: Int): Int = (
                matrix[offset] * red + matrix[offset + 1] * green + matrix[offset + 2] * blue +
                    matrix[offset + 3] * alpha + matrix[offset + 4]
                ).coerceIn(0f, 255f).roundToInt()
            return Rgba(row(0), row(5), row(10), row(15))
        }

        private fun percent(value: String?): Double {
            val number = value?.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return 0.0
            return number / PERCENT_UNIT
        }
    }
}

private fun DuotoneEffect.applyTo(color: Rgba): Rgba {
    val position = (LUMA_RED * color.red + LUMA_GREEN * color.green + LUMA_BLUE * color.blue) / 255.0
    return Rgba(
        (dark.red + (light.red - dark.red) * position).roundToInt(),
        (dark.green + (light.green - dark.green) * position).roundToInt(),
        (dark.blue + (light.blue - dark.blue) * position).roundToInt(),
        color.alpha,
    )
}

private fun ColorChangeEffect.applyTo(color: Rgba): Rgba {
    val matches = abs(color.red - from.red) <= COLOR_TOLERANCE &&
        abs(color.green - from.green) <= COLOR_TOLERANCE &&
        abs(color.blue - from.blue) <= COLOR_TOLERANCE
    return if (matches) to.copy(alpha = color.alpha) else color
}

private fun TintEffect.applyTo(color: Rgba): Rgba {
    val weight = amount.coerceIn(0.0, 1.0)
    return Rgba(
        (color.red * (1 - weight) + this.color.red * weight).roundToInt(),
        (color.green * (1 - weight) + this.color.green * weight).roundToInt(),
        (color.blue * (1 - weight) + this.color.blue * weight).roundToInt(),
        color.alpha,
    )
}

private fun HslEffect.applyTo(color: Rgba): Rgba {
    val hsl = Hsl.of(color.red / 255.0, color.green / 255.0, color.blue / 255.0)
    return hsl.copy(
        hue = hsl.hue + hueDegrees,
        saturation = hsl.saturation + saturation,
        luminance = hsl.luminance + luminance,
    ).toRgba().copy(alpha = color.alpha)
}
