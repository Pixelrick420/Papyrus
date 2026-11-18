package io.github.pixelrick420.papyrus.viewer.pptx

import kotlin.math.roundToInt

/** The blip-level recolour effects, carried from the parser to the raster backend. */
internal data class PictureEffects(val matrix: List<Float>? = null) {

    val isEmpty: Boolean get() = matrix == null

    fun applyTo(color: Rgba): Rgba = matrix?.let { applyMatrix(it, color) } ?: color

    companion object {

        val EMPTY = PictureEffects()

        private const val PERCENT_UNIT = 100_000.0
        private const val LUMA_RED = 0.299f
        private const val LUMA_GREEN = 0.587f
        private const val LUMA_BLUE = 0.114f
        private const val BI_LEVEL_FACTOR = 1_024f

        fun of(blip: XNode?): PictureEffects {
            var matrix: List<Float>? = null
            for (child in blip?.children.orEmpty()) {
                if (child.name.namespace != OoxmlNamespace.A) continue
                val step = when (child.name.localName) {
                    "lum" -> {
                        val bright = percent(child.attribute("bright"))
                        val contrast = percent(child.attribute("contrast"))
                        if (bright == 0.0 && contrast == 0.0) null else luminance(bright, contrast)
                    }
                    "biLevel" -> biLevel(percent(child.attribute("thresh")))
                    "grayscl" -> grayscale()
                    else -> null
                } ?: continue
                matrix = if (matrix == null) step else concat(step, matrix)
            }
            return PictureEffects(matrix)
        }

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
                LUMA_RED * BI_LEVEL_FACTOR, LUMA_GREEN * BI_LEVEL_FACTOR, LUMA_BLUE * BI_LEVEL_FACTOR, 0f, offset,
                LUMA_RED * BI_LEVEL_FACTOR, LUMA_GREEN * BI_LEVEL_FACTOR, LUMA_BLUE * BI_LEVEL_FACTOR, 0f, offset,
                LUMA_RED * BI_LEVEL_FACTOR, LUMA_GREEN * BI_LEVEL_FACTOR, LUMA_BLUE * BI_LEVEL_FACTOR, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            )
        }

        private fun grayscale(): List<Float> = listOf(
            LUMA_RED, LUMA_GREEN, LUMA_BLUE, 0f, 0f,
            LUMA_RED, LUMA_GREEN, LUMA_BLUE, 0f, 0f,
            LUMA_RED, LUMA_GREEN, LUMA_BLUE, 0f, 0f,
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
