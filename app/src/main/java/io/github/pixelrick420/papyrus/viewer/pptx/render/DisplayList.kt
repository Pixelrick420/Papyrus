package io.github.pixelrick420.papyrus.viewer.pptx.render

import io.github.pixelrick420.papyrus.viewer.pptx.Affine
import io.github.pixelrick420.papyrus.viewer.pptx.PatternTile
import io.github.pixelrick420.papyrus.viewer.pptx.PictureEffects
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.StrokeCap
import io.github.pixelrick420.papyrus.viewer.pptx.StrokeJoin
import io.github.pixelrick420.papyrus.viewer.pptx.text.ResolvedFont
import kotlin.math.roundToInt

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

internal enum class GradientTileMode { CLAMP, REPEAT, MIRROR }

internal sealed interface PaintSpec {
    data class Solid(val color: Rgba) : PaintSpec

    data class Linear(
        val start: PointF,
        val end: PointF,
        val stops: List<ColorStop>,
        val tileMode: GradientTileMode = GradientTileMode.CLAMP,
    ) : PaintSpec

    data class Radial(
        val center: PointF,
        val radius: Float,
        val stops: List<ColorStop>,
        val tileMode: GradientTileMode = GradientTileMode.CLAMP,
    ) : PaintSpec

    data class Pattern(
        val tile: PatternTile,
        val foreground: Rgba,
        val background: Rgba?,
        val scale: Float,
        val rotationDegrees: Float = 0f,
    ) : PaintSpec
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

internal data class ImageEffects(
    val alpha: Float = 1f,
    val clip: PathData? = null,
    val recolor: PictureEffects = PictureEffects.EMPTY,
)

internal enum class TextRuleKind { UNDERLINE, STRIKE }

/** One underline or strike rule the engine draws itself; [y] is the rule's centre, in the run's space. */
internal data class TextRule(
    val kind: TextRuleKind,
    val style: String,
    val left: Float,
    val right: Float,
    val y: Float,
    val thickness: Float,
    /** `uFill` when it outranks the run's own fill. */
    val paint: PaintSpec? = null,
)

/** A stroke that follows a run's glyphs, with [width] in the run's own units. */
internal data class TextOutlineSpec(val width: Float, val paint: PaintSpec)

internal data class GlyphRun(
    val text: String,
    val x: Float,
    val y: Float,
    val paint: PaintSpec,
    val size: Float,
    val family: String? = null,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val letterSpacing: Float = 0f,
    val kerning: Boolean = false,
    val ligatures: Boolean = false,
    val outline: TextOutlineSpec? = null,
    val shadow: ShadowSpec? = null,
    val rules: List<TextRule> = emptyList(),
    /** The bundled or system face the text pass resolved; when null the backend builds one from [family]. */
    val font: ResolvedFont? = null,
    /** Clockwise degrees to turn the run about its baseline start when it is drawn. */
    val rotationDeg: Double = 0.0,
)

internal data class ShadowSpec(val color: Rgba, val blur: Float, val dx: Float, val dy: Float)

/** A blurred copy of a path; [transform] carries distance, scale, skew and alignment as one affine. */
internal data class ShapeShadowSpec(
    val color: Rgba,
    val blur: Float,
    val transform: Affine,
    val inner: Boolean = false,
)

internal data class ReflectionSpec(
    val blur: Float,
    val distance: Float,
    val directionDegrees: Float,
    val startAlpha: Float,
    val endAlpha: Float,
    val startPosition: Float,
    val endPosition: Float,
)

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

    data class ShapeShadow(val path: PathData, val shadow: ShapeShadowSpec) : DrawOp

    data class Glow(val path: PathData, val color: Rgba, val radius: Float) : DrawOp

    data class SoftEdge(val path: PathData, val paint: PaintSpec, val radius: Float) : DrawOp

    data class Blur(val path: PathData, val paint: PaintSpec, val radius: Float) : DrawOp

    data class Reflection(val path: PathData, val paint: PaintSpec, val spec: ReflectionSpec) : DrawOp
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

    fun shapeShadow(path: PathData, shadow: ShapeShadowSpec)

    fun glow(path: PathData, color: Rgba, radius: Float)

    fun softEdge(path: PathData, paint: PaintSpec, radius: Float)

    fun blur(path: PathData, paint: PaintSpec, radius: Float)

    fun reflection(path: PathData, paint: PaintSpec, spec: ReflectionSpec)
}

internal data class RenderRegion(val source: RectF, val scale: Float) {

    val widthPx: Int get() = (source.width * scale).roundToInt().coerceAtLeast(1)

    val heightPx: Int get() = (source.height * scale).roundToInt().coerceAtLeast(1)
}

/** [complete] is false when the renderer ran out of time and stopped part-way through the slide. */
internal class DisplayList(val ops: List<DrawOp>, val complete: Boolean = true) {

    fun playRegion(target: DrawTarget, region: RenderRegion) {
        target.save()
        target.clip(PathData.rect(RectF(0f, 0f, region.widthPx.toFloat(), region.heightPx.toFloat())))
        target.concat(
            Affine(
                a = region.scale.toDouble(),
                b = 0.0,
                c = 0.0,
                d = region.scale.toDouble(),
                e = -region.source.left * region.scale.toDouble(),
                f = -region.source.top * region.scale.toDouble(),
            ),
        )
        play(target)
        target.restore()
    }

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
                is DrawOp.ShapeShadow -> target.shapeShadow(op.path, op.shadow)
                is DrawOp.Glow -> target.glow(op.path, op.color, op.radius)
                is DrawOp.SoftEdge -> target.softEdge(op.path, op.paint, op.radius)
                is DrawOp.Blur -> target.blur(op.path, op.paint, op.radius)
                is DrawOp.Reflection -> target.reflection(op.path, op.paint, op.spec)
            }
        }
    }
}
