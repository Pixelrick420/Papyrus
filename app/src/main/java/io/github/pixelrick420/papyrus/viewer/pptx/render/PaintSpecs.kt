package io.github.pixelrick420.papyrus.viewer.pptx.render

import io.github.pixelrick420.papyrus.viewer.pptx.LinearGradient
import io.github.pixelrick420.papyrus.viewer.pptx.RadialGradient
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** Turns a scene's paint into the display list's, with a gradient's extents measured in the shape's own box. */
internal object PaintSpecs {

    fun of(paint: ScenePaint?, box: SceneRect): PaintSpec? = when (paint) {
        is ScenePaint.Solid -> PaintSpec.Solid(paint.color)
        is ScenePaint.Linear -> linear(paint.gradient, box)
        is ScenePaint.Radial -> radial(paint.gradient, box)
        is ScenePaint.Pattern -> PaintSpec.Pattern(
            paint.tile,
            paint.foreground,
            paint.background,
            paint.scale.toFloat(),
        )
        else -> null
    }

    fun linear(gradient: LinearGradient, box: SceneRect): PaintSpec.Linear {
        val radians = Math.toRadians(gradient.angleIn(box))
        val dirX = cos(radians)
        val dirY = sin(radians)
        val length = abs(dirX) * box.width + abs(dirY) * box.height
        val centreX = box.width / 2.0
        val centreY = box.height / 2.0
        return PaintSpec.Linear(
            PointF((centreX - dirX * length / 2.0).toFloat(), (centreY - dirY * length / 2.0).toFloat()),
            PointF((centreX + dirX * length / 2.0).toFloat(), (centreY + dirY * length / 2.0).toFloat()),
            gradient.stops.map { ColorStop(it.offset.toFloat(), it.color) },
        )
    }

    fun radial(gradient: RadialGradient, box: SceneRect): PaintSpec.Radial = PaintSpec.Radial(
        PointF((gradient.centerX * box.width).toFloat(), (gradient.centerY * box.height).toFloat()),
        (max(box.width, box.height) / 2.0).toFloat(),
        gradient.stops.map { ColorStop(it.offset.toFloat(), it.color) },
    )
}
