package io.github.pixelrick420.papyrus.viewer.pptx.render

import io.github.pixelrick420.papyrus.viewer.pptx.Affine

/** A [DrawTarget] that keeps every op, so a test can assert on the display list without pixels. */
internal class RecordingBackend : DrawTarget {

    private val recorded = mutableListOf<DrawOp>()

    private val recordedClipDepths = mutableListOf<Int>()

    val ops: List<DrawOp> get() = recorded

    /** The save depth at the moment each [DrawOp.Clip] arrived, so a test can check the clip stack. */
    val clipDepths: List<Int> get() = recordedClipDepths

    var depth: Int = 0
        private set

    fun play(list: DisplayList) = list.play(this)

    override fun fillPath(path: PathData, paint: PaintSpec) {
        recorded += DrawOp.FillPath(path, paint)
    }

    override fun strokePath(path: PathData, stroke: StrokeSpec) {
        recorded += DrawOp.StrokePath(path, stroke)
    }

    override fun drawImage(image: ImageRef, src: RectF, dst: RectF, effects: ImageEffects) {
        recorded += DrawOp.DrawImage(image, src, dst, effects)
    }

    override fun drawGlyphRun(run: GlyphRun) {
        recorded += DrawOp.DrawGlyphRun(run)
    }

    override fun save() {
        recorded += DrawOp.Save
        depth++
    }

    override fun restore() {
        recorded += DrawOp.Restore
        if (depth > 0) depth--
    }

    override fun concat(m: Affine) {
        recorded += DrawOp.Concat(m)
    }

    override fun clip(path: PathData) {
        recorded += DrawOp.Clip(path)
        recordedClipDepths += depth
    }

    override fun shadow(shadow: ShadowSpec?) {
        recorded += DrawOp.Shadow(shadow)
    }
}
