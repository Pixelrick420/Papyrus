package io.github.pixelrick420.papyrus.viewer.pptx

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * A 2D affine transform in EMU: `x' = a*x + c*y + e`, `y' = b*x + d*y + f`.
 *
 * Composition follows PowerPoint's order for an `a:xfrm`: scale into the parent space, flip about the frame
 * centre, rotate about the centre, then place at the frame offset.
 */
internal class Affine(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double,
) {

    fun map(point: ScenePoint): ScenePoint = map(point.x, point.y)

    fun map(x: Double, y: Double): ScenePoint =
        ScenePoint(a * x + c * y + e, b * x + d * y + f)

    /** Applies this transform first, then [next]. */
    fun then(next: Affine): Affine = Affine(
        a = next.a * a + next.c * b,
        b = next.b * a + next.d * b,
        c = next.a * c + next.c * d,
        d = next.b * c + next.d * d,
        e = next.a * e + next.c * f + next.e,
        f = next.b * e + next.d * f + next.f,
    )

    companion object {

        val IDENTITY = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        /** The largest shape or slide edge, in EMU: the 56-inch bound every later geometry pass shares. */
        val MAX_EDGE_EMU: Double = 56.0 * 914_400.0

        fun translate(dx: Double, dy: Double) = Affine(1.0, 0.0, 0.0, 1.0, dx, dy)

        fun scale(sx: Double, sy: Double) = Affine(sx, 0.0, 0.0, sy, 0.0, 0.0)

        /** Positive [radians] turns clockwise, as a y-down EMU space expects. */
        fun rotate(radians: Double): Affine {
            val cosine = cos(radians)
            val sine = sin(radians)
            return Affine(cosine, sine, -sine, cosine, 0.0, 0.0)
        }

        fun flip(horizontal: Boolean, vertical: Boolean): Affine =
            scale(if (horizontal) -1.0 else 1.0, if (vertical) -1.0 else 1.0)

        fun about(centre: ScenePoint, transform: Affine): Affine =
            translate(-centre.x, -centre.y).then(transform).then(translate(centre.x, centre.y))

        /**
         * Maps a shape's local coordinates into its parent: a group's [childFrame] maps onto [frame], then the
         * frame's flip and rotation apply about its centre. A leaf shape starts at `0,0` inside its own frame.
         */
        fun forShape(
            frame: SceneRect,
            childFrame: SceneRect? = null,
            rotation: Double = 0.0,
            flipH: Boolean = false,
            flipV: Boolean = false,
            maxEmu: Double = MAX_EDGE_EMU,
        ): Affine {
            val x = frame.x.clamped(maxEmu)
            val y = frame.y.clamped(maxEmu)
            val width = frame.width.clamped(maxEmu)
            val height = frame.height.clamped(maxEmu)
            val childX = childFrame?.x?.clamped(maxEmu) ?: 0.0
            val childY = childFrame?.y?.clamped(maxEmu) ?: 0.0
            val sx = scaleOf(width, childFrame?.width?.clamped(maxEmu))
            val sy = scaleOf(height, childFrame?.height?.clamped(maxEmu))

            var transform = scale(sx, sy)
                .then(translate(x - childX * sx, y - childY * sy))
            val centre = ScenePoint(x + width / 2.0, y + height / 2.0)
            if (flipH || flipV) transform = transform.then(about(centre, flip(flipH, flipV)))
            if (rotation != 0.0) transform = transform.then(about(centre, rotate(rotation * RADIANS_PER_UNIT)))
            return transform
        }

        private fun scaleOf(extent: Double, childExtent: Double?): Double =
            if (childExtent == null || abs(childExtent) < EPSILON) 1.0 else extent / childExtent

        private const val RADIANS_PER_UNIT = PI / 10_800_000.0

        private const val EPSILON = 1e-9
    }
}

private fun Double.clamped(max: Double): Double = coerceIn(-max, max)
