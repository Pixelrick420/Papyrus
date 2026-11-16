package io.github.pixelrick420.papyrus.viewer.pptx.render

import io.github.pixelrick420.papyrus.viewer.pptx.Affine
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayListTest {

    private val path = PathData.rect(RectF(1f, 2f, 30f, 40f))

    private val affine = Affine.translate(5.0, 6.0)

    @Test
    fun `playback records every op in order`() {
        val ops = listOf(
            DrawOp.Save,
            DrawOp.Concat(affine),
            DrawOp.Clip(path),
            DrawOp.Shadow(ShadowSpec(Rgba.BLACK, blur = 4f, dx = 0f, dy = 2f)),
            DrawOp.FillPath(path, PaintSpec.Solid(Rgba(0x44, 0x72, 0xC4))),
            DrawOp.StrokePath(path, StrokeSpec(PaintSpec.Solid(Rgba.WHITE), width = 2f)),
            DrawOp.DrawImage(
                ImageRef("ppt/media/image1.png", 10, 10),
                RectF.EMPTY,
                RectF(0f, 0f, 10f, 10f),
                ImageEffects(alpha = 0.5f),
            ),
            DrawOp.DrawGlyphRun(GlyphRun("Hi", 0f, 12f, PaintSpec.Solid(Rgba.BLACK), size = 12f)),
            DrawOp.Shadow(null),
            DrawOp.Restore,
        )

        val recorder = RecordingBackend()
        recorder.play(DisplayList(ops))

        assertEquals(ops, recorder.ops)
    }

    @Test
    fun `a glyph run keeps its outline and shadow through playback`() {
        val outline = TextOutlineSpec(2f, PaintSpec.Solid(Rgba(0x11, 0x22, 0x33)))
        val shadow = ShadowSpec(Rgba.BLACK, blur = 3f, dx = 1f, dy = 2f)
        val ops = listOf(
            DrawOp.DrawGlyphRun(
                GlyphRun(
                    text = "Hi",
                    x = 0f,
                    y = 12f,
                    paint = PaintSpec.Solid(Rgba.BLACK),
                    size = 12f,
                    outline = outline,
                    shadow = shadow,
                ),
            ),
        )

        val recorder = RecordingBackend()
        recorder.play(DisplayList(ops))

        val run = recorder.ops.filterIsInstance<DrawOp.DrawGlyphRun>().single().run
        assertEquals(outline, run.outline)
        assertEquals(shadow, run.shadow)
    }

    @Test
    fun `a glyph run keeps a gradient fill through playback`() {
        val fill = PaintSpec.Linear(
            start = PointF(0f, 0f),
            end = PointF(20f, 0f),
            stops = listOf(ColorStop(0f, Rgba(255, 0, 0)), ColorStop(1f, Rgba(0, 0, 255))),
        )

        val recorder = RecordingBackend()
        recorder.play(
            DisplayList(
                listOf(
                    DrawOp.DrawGlyphRun(
                        GlyphRun("Hi", 0f, 12f, fill, size = 12f),
                    ),
                ),
            ),
        )

        val run = recorder.ops.filterIsInstance<DrawOp.DrawGlyphRun>().single().run
        assertEquals(fill, run.paint)
    }

    @Test
    fun `a fill records its colour and path`() {
        val color = Rgba(0x44, 0x72, 0xC4)
        val recorder = RecordingBackend()
        recorder.play(DisplayList(listOf(DrawOp.FillPath(path, PaintSpec.Solid(color)))))

        val fill = recorder.ops.filterIsInstance<DrawOp.FillPath>().single()
        assertEquals(PaintSpec.Solid(color), fill.paint)
        assertEquals(path, fill.path)
    }

    @Test
    fun `save, clip and restore track the clip stack`() {
        val recorder = RecordingBackend()
        recorder.play(
            DisplayList(
                listOf(
                    DrawOp.Save,
                    DrawOp.Clip(path),
                    DrawOp.Save,
                    DrawOp.Clip(path),
                    DrawOp.Restore,
                    DrawOp.Restore,
                ),
            ),
        )

        assertEquals(listOf(1, 2), recorder.clipDepths)
        assertEquals(0, recorder.depth)
    }

    @Test
    fun `a restore without a save leaves the depth at zero`() {
        val recorder = RecordingBackend()
        recorder.play(DisplayList(listOf(DrawOp.Restore)))

        assertEquals(0, recorder.depth)
    }
}
