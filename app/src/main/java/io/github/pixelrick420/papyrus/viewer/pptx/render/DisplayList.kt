package io.github.pixelrick420.papyrus.viewer.pptx.render

import io.github.pixelrick420.papyrus.viewer.pptx.Affine
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.StrokeCap
import io.github.pixelrick420.papyrus.viewer.pptx.StrokeJoin

internal data class PointF(val x: Float, val y: Float)

internal data class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    val width: Float get() = right - left

    val height: Float get() = bottom - top

    companion object {
        val EMPTY = RectF(0f, 0f, 0f, 0f)
    }
}

internal sealed interface PathSegment {
    data class MoveTo(val x: Float, val y: Float) : PathSegment

    data class LineTo(val x: Float, val y: Float) : PathSegment

    data class QuadTo(val controlX: Float, val controlY: Float, val x: Float, val y: Float) : PathSegment

    data class CubicTo(
        val control1X: Float,
        val control1Y: Float,
        val control2X: Float,
        val control2Y: Float,
        val x: Float,
        val y: Float,
    ) : PathSegment

    data object Close : PathSegment
}

internal enum class PathFill {
    WINDING,
    EVEN_ODD,
}

internal data class PathData(
    val segments: List<PathSegment>,
    val fill: PathFill = PathFill.WINDING,
) {

    companion object {

        fun rect(rect: RectF): PathData = PathData(
            listOf(
                PathSegment.MoveTo(rect.left, rect.top),
                PathSegment.LineTo(rect.right, rect.top),
                PathSegment.LineTo(rect.right, rect.bottom),
                PathSegment.LineTo(rect.left, rect.bottom),
                PathSegment.Close,
            ),
        )
    }
}

internal data class ColorStop(val offset: Float, val color: Rgba)

internal sealed interface PaintSpec {
    data class Solid(val color: Rgba) : PaintSpec

    data class Linear(val start: PointF, val end: PointF, val stops: List<ColorStop>) : PaintSpec

    data class Radial(val center: PointF, val radius: Float, val stops: List<ColorStop>) : PaintSpec
}

internal data class StrokeSpec(
    val paint: PaintSpec,
    val width: Float,
    val cap: StrokeCap = StrokeCap.FLAT,
    val join: StrokeJoin = StrokeJoin.ROUND,
    val miterLimit: Float = 10f,
    val dash: List<Float> = emptyList(),
)

/** Identifies a decoded image by [key]; the backend resolves it to pixels. */
internal data class ImageRef(val key: String, val width: Int = 0, val height: Int = 0)

internal data class ImageEffects(val alpha: Float = 1f, val clip: PathData? = null)

internal data class GlyphRun(
    val text: String,
    val x: Float,
    val y: Float,
    val paint: PaintSpec,
    val size: Float,
    val family: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
)

internal data class ShadowSpec(val color: Rgba, val blur: Float, val dx: Float, val dy: Float)

internal sealed interface DrawOp {
    data class FillPath(val path: PathData, val paint: PaintSpec) : DrawOp

    data class StrokePath(val path: PathData, val stroke: StrokeSpec) : DrawOp

    data class DrawImage(
        val image: ImageRef,
        val src: RectF,
        val dst: RectF,
        val effects: ImageEffects = ImageEffects(),
    ) : DrawOp

    data class DrawGlyphRun(val run: GlyphRun) : DrawOp

    data object Save : DrawOp

    data object Restore : DrawOp

    data class Concat(val matrix: Affine) : DrawOp

    data class Clip(val path: PathData) : DrawOp

    data class Shadow(val shadow: ShadowSpec?) : DrawOp
}

internal interface DrawTarget {
    fun fillPath(path: PathData, paint: PaintSpec)

    fun strokePath(path: PathData, stroke: StrokeSpec)

    fun drawImage(image: ImageRef, src: RectF, dst: RectF, effects: ImageEffects)

    fun drawGlyphRun(run: GlyphRun)

    fun save()

    fun restore()

    fun concat(m: Affine)

    fun clip(path: PathData)

    fun shadow(shadow: ShadowSpec?)
}

internal class DisplayList(val ops: List<DrawOp>) {

    fun play(target: DrawTarget) {
        for (op in ops) {
            when (op) {
                is DrawOp.FillPath -> target.fillPath(op.path, op.paint)
                is DrawOp.StrokePath -> target.strokePath(op.path, op.stroke)
                is DrawOp.DrawImage -> target.drawImage(op.image, op.src, op.dst, op.effects)
                is DrawOp.DrawGlyphRun -> target.drawGlyphRun(op.run)
                DrawOp.Save -> target.save()
                DrawOp.Restore -> target.restore()
                is DrawOp.Concat -> target.concat(op.matrix)
                is DrawOp.Clip -> target.clip(op.path)
                is DrawOp.Shadow -> target.shadow(op.shadow)
            }
        }
    }
}
