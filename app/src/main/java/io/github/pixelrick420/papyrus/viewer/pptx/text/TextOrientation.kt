package io.github.pixelrick420.papyrus.viewer.pptx.text

/**
 * A shape's rotation and flips as the caller reports them. `flipH` is carried for callers but never
 * mirrors text (plan §3.3 #12).
 */
internal data class TextShapeTransform(
    val rotationDeg: Double = 0.0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
) {

    companion object {

        val NONE = TextShapeTransform()
    }
}

/** How far the text block turns clockwise, from the shape and the body's own `rot` (plan §3.3 #12). */
internal data class TextOrientation(val rotationDeg: Double = 0.0) {

    companion object {

        val NONE = TextOrientation()

        fun of(shape: TextShapeTransform, properties: TextBodyProperties): TextOrientation {
            val shapeRotation = if (properties.upright) 0.0 else shape.rotationDeg
            val flip = if (shape.flipV) HALF_TURN_DEG else 0.0
            return TextOrientation(normalized(properties.rotation + shapeRotation + flip))
        }

        private fun normalized(degrees: Double): Double {
            val turn = degrees % FULL_TURN_DEG
            return if (turn < 0.0) turn + FULL_TURN_DEG else turn
        }

        private const val FULL_TURN_DEG = 360.0

        private const val HALF_TURN_DEG = 180.0
    }
}
