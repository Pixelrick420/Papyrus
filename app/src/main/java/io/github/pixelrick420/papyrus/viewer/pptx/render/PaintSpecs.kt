package io.github.pixelrick420.papyrus.viewer.pptx.render

import io.github.pixelrick420.papyrus.viewer.pptx.GradientFlip
import io.github.pixelrick420.papyrus.viewer.pptx.GradientStop
import io.github.pixelrick420.papyrus.viewer.pptx.GradientTile
import io.github.pixelrick420.papyrus.viewer.pptx.LinearGradient
import io.github.pixelrick420.papyrus.viewer.pptx.PathGradient
import io.github.pixelrick420.papyrus.viewer.pptx.RadialGradient
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Turns a scene's paint into the display list's, with a gradient's extents measured in the shape's own box. */
internal object PaintSpecs {

    fun of(paint: ScenePaint?, box: SceneRect, rotationDegrees: Double = 0.0): PaintSpec? = when (paint) {
        is ScenePaint.Solid -> PaintSpec.Solid(paint.color)
        is ScenePaint.Linear -> linear(paint.gradient, box, rotationDegrees)
        is ScenePaint.Radial -> radial(paint.gradient, box, rotationDegrees)
        is ScenePaint.Path -> path(paint.gradient, box, rotationDegrees)
        is ScenePaint.Pattern -> PaintSpec.Pattern(
            paint.tile,
            paint.foreground,
            paint.background,
            paint.scale.toFloat(),
            counterRotation(paint.rotatesWithShape, rotationDegrees),
        )
        else -> null
    }

    fun linear(gradient: LinearGradient, box: SceneRect, rotationDegrees: Double = 0.0): PaintSpec.Linear {
        val tile = tileBoxOf(box, gradient.tile)
        val radians = Math.toRadians(gradient.angleIn(tile))
        val dirX = cos(radians)
        val dirY = sin(radians)
        val length = abs(dirX) * tile.width + abs(dirY) * tile.height
        val centreX = tile.x + tile.width / 2.0
        val centreY = tile.y + tile.height / 2.0
        var start = PointF((centreX - dirX * length / 2.0).toFloat(), (centreY - dirY * length / 2.0).toFloat())
        var end = PointF((centreX + dirX * length / 2.0).toFloat(), (centreY + dirY * length / 2.0).toFloat())
        val counter = counterRotation(gradient.rotatesWithShape, rotationDegrees)
        if (counter != 0f) {
            val centre = shapeCentre(box)
            start = rotate(start, centre, counter)
            end = rotate(end, centre, counter)
        }
        return PaintSpec.Linear(start, end, stopsOf(gradient.stops), tileModeOf(gradient.tile))
    }

    fun radial(gradient: RadialGradient, box: SceneRect, rotationDegrees: Double = 0.0): PaintSpec.Radial {
        val tile = tileBoxOf(box, gradient.tile)
        var centre = PointF(
            (tile.x + gradient.centerX * tile.width).toFloat(),
            (tile.y + gradient.centerY * tile.height).toFloat(),
        )
        val counter = counterRotation(gradient.rotatesWithShape, rotationDegrees)
        if (counter != 0f) centre = rotate(centre, shapeCentre(box), counter)
        return PaintSpec.Radial(
            centre,
            (max(tile.width, tile.height) / 2.0).toFloat(),
            stopsOf(gradient.stops),
            tileModeOf(gradient.tile),
        )
    }

    fun path(gradient: PathGradient, box: SceneRect, rotationDegrees: Double = 0.0): PaintSpec.Linear {
        val tile = tileBoxOf(box, gradient.tile)
        val start = PointF((tile.x + gradient.left * tile.width).toFloat(), (tile.y + gradient.top * tile.height).toFloat())
        val end = PointF(
            (tile.x + (1.0 - gradient.right) * tile.width).toFloat(),
            (tile.y + (1.0 - gradient.bottom) * tile.height).toFloat(),
        )
        val stops = stopsOf(gradient.stops)
        val mode = tileModeOf(gradient.tile)
        val counter = counterRotation(gradient.rotatesWithShape, rotationDegrees)
        if (counter == 0f) return PaintSpec.Linear(start, end, stops, mode)
        val centre = shapeCentre(box)
        return PaintSpec.Linear(rotate(start, centre, counter), rotate(end, centre, counter), stops, mode)
    }

    private fun shapeCentre(box: SceneRect): PointF =
        PointF((box.x + box.width / 2.0).toFloat(), (box.y + box.height / 2.0).toFloat())

    private fun tileBoxOf(box: SceneRect, tile: GradientTile): SceneRect {
        if (tile.isEmpty) return box
        val left = box.width * tile.left
        val top = box.height * tile.top
        return SceneRect(
            box.x + left,
            box.y + top,
            (box.width * (1.0 - tile.left - tile.right)).coerceAtLeast(0.0),
            (box.height * (1.0 - tile.top - tile.bottom)).coerceAtLeast(0.0),
        )
    }

    private fun tileModeOf(tile: GradientTile): GradientTileMode = when {
        tile.flip != GradientFlip.NONE -> GradientTileMode.MIRROR
        !tile.isEmpty -> GradientTileMode.REPEAT
        else -> GradientTileMode.CLAMP
    }

    private fun stopsOf(stops: List<GradientStop>): List<ColorStop> =
        stops.map { ColorStop(it.offset.toFloat(), it.color) }

    private fun counterRotation(rotatesWithShape: Boolean, rotationDegrees: Double): Float =
        if (rotatesWithShape || rotationDegrees == 0.0) 0f else -rotationDegrees.toFloat()

    private fun rotate(point: PointF, centre: PointF, degrees: Float): PointF {
        val radians = Math.toRadians(degrees.toDouble())
        val c = cos(radians)
        val s = sin(radians)
        val dx = point.x - centre.x
        val dy = point.y - centre.y
        return PointF(centre.x + (c * dx - s * dy).toFloat(), centre.y + (s * dx + c * dy).toFloat())
    }
}
