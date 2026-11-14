package io.github.pixelrick420.papyrus.viewer.pptx.render

import io.github.pixelrick420.papyrus.viewer.pptx.ImageBounds
import io.github.pixelrick420.papyrus.viewer.pptx.LinearGradient
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlAttribute
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlName
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlNamespace
import io.github.pixelrick420.papyrus.viewer.pptx.PictureCrop
import io.github.pixelrick420.papyrus.viewer.pptx.PictureFill
import io.github.pixelrick420.papyrus.viewer.pptx.PictureSource
import io.github.pixelrick420.papyrus.viewer.pptx.PictureTile
import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import io.github.pixelrick420.papyrus.viewer.pptx.PresetGeometry
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.Scene
import io.github.pixelrick420.papyrus.viewer.pptx.SceneConnector
import io.github.pixelrick420.papyrus.viewer.pptx.SceneFormat
import io.github.pixelrick420.papyrus.viewer.pptx.SceneGroup
import io.github.pixelrick420.papyrus.viewer.pptx.SceneLine
import io.github.pixelrick420.papyrus.viewer.pptx.SceneNode
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePicture
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePreset
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import io.github.pixelrick420.papyrus.viewer.pptx.SceneShape
import io.github.pixelrick420.papyrus.viewer.pptx.SceneSize
import io.github.pixelrick420.papyrus.viewer.pptx.XNode
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class SlideRendererTest {

    private val presets = PresetGeometry(
        open = { File("src/main/assets/ooxml/presetShapeDefinitions.xml").inputStream() },
        newParserFactory = { KXmlParser() },
    )

    private val renderer = SlideRenderer(presets)

    private val red = Rgba(0xC0, 0x00, 0x00)

    private val black = Rgba(0, 0, 0)

    @Test
    fun `the background is painted before the shapes`() = runBlocking {
        val ops = renderer.render(scene(rect(), background = ScenePaint.Solid(red)))!!.ops

        assertEquals(
            DrawOp.FillPath(PathData.rect(RectF(0f, 0f, 400f, 200f)), PaintSpec.Solid(red)),
            ops.first(),
        )
        assertTrue(ops[1] is DrawOp.Save)
    }

    @Test
    fun `a gradient background spans the slide`() = runBlocking {
        val gradient = LinearGradient(
            angleDegrees = 0.0,
            scaled = true,
            stops = listOf(
                io.github.pixelrick420.papyrus.viewer.pptx.GradientStop(0.0, red),
                io.github.pixelrick420.papyrus.viewer.pptx.GradientStop(1.0, black),
            ),
        )

        val paint = (renderer.render(scene(background = ScenePaint.Linear(gradient)))!!.ops.single()
            as DrawOp.FillPath).paint as PaintSpec.Linear

        assertEquals(PointF(0f, 100f), paint.start)
        assertEquals(PointF(400f, 100f), paint.end)
        assertEquals(2, paint.stops.size)
    }

    @Test
    fun `a shape fills its outline then strokes it`() = runBlocking {
        val line = SceneLine(widthEmu = 12_700.0, paint = ScenePaint.Solid(black), dash = null)
        val ops = renderer.render(scene(rect(format = SceneFormat(ScenePaint.Solid(red), line, null))))!!.ops

        assertEquals(5, ops.size)
        assertTrue(ops[0] is DrawOp.Save)
        assertTrue(ops[1] is DrawOp.Concat)
        assertEquals(
            PathData.rect(RectF(0f, 0f, 30f, 40f)),
            (ops[2] as DrawOp.FillPath).path,
        )
        assertEquals(PaintSpec.Solid(red), (ops[2] as DrawOp.FillPath).paint)
        val stroke = (ops[3] as DrawOp.StrokePath).stroke
        assertEquals(PaintSpec.Solid(black), stroke.paint)
        assertEquals(12_700f, stroke.width, 0f)
        assertEquals(8f, stroke.miterLimit, 0f)
        assertTrue(stroke.dash.isEmpty())
        assertTrue(ops[4] is DrawOp.Restore)
    }

    @Test
    fun `a group maps its child frame onto its own frame`() = runBlocking {
        val group = SceneGroup(
            id = 2,
            name = null,
            frame = SceneRect(0.0, 0.0, 200.0, 100.0),
            childFrame = SceneRect(100.0, 100.0, 100.0, 100.0),
            children = listOf(rect(frame = SceneRect(100.0, 100.0, 50.0, 50.0))),
        )

        val ops = renderer.render(scene(group))!!.ops

        assertEquals(7, ops.size)
        val groupMap = (ops[1] as DrawOp.Concat).matrix
        val origin = groupMap.map(100.0, 100.0)
        val far = groupMap.map(200.0, 200.0)
        assertEquals(0.0, origin.x, 1e-9)
        assertEquals(0.0, origin.y, 1e-9)
        assertEquals(200.0, far.x, 1e-9)
        assertEquals(100.0, far.y, 1e-9)
        assertTrue(ops[2] is DrawOp.Save)
        assertTrue(ops[3] is DrawOp.Concat)
        assertTrue(ops[4] is DrawOp.FillPath)
        assertTrue(ops[5] is DrawOp.Restore)
        assertTrue(ops[6] is DrawOp.Restore)
    }

    @Test
    fun `a rotated shape turns about its frame centre`() = runBlocking {
        val ops = renderer.render(scene(rect(transform = xfrm("rot" to "5400000"))))!!.ops

        val map = (ops[1] as DrawOp.Concat).matrix
        val topLeft = map.map(0.0, 0.0)
        val centre = map.map(15.0, 20.0)

        assertEquals(45.0, topLeft.x, 1e-6)
        assertEquals(25.0, topLeft.y, 1e-6)
        assertEquals(25.0, centre.x, 1e-9)
        assertEquals(40.0, centre.y, 1e-9)
        assertEquals(1.0, map.b, 1e-9)
    }

    @Test
    fun `a cropped picture draws the cropped source pixels into its frame`() = runBlocking {
        val picture = picture(
            source = PictureSource(
                alpha = 0.5,
                crop = PictureCrop(left = 0.25, top = 0.2, right = 0.25, bottom = 0.2),
            ),
        )
        val resolver = SlidePictureResolver { SlideImage(File("image1.png"), ImageBounds(100, 50)) }

        val ops = renderer.render(scene(picture), resolver)!!.ops

        assertEquals(4, ops.size)
        assertTrue(ops[0] is DrawOp.Save)
        assertTrue(ops[1] is DrawOp.Concat)
        val image = ops[2] as DrawOp.DrawImage
        assertEquals("image1.png", image.image.key)
        assertEquals(ImageRef("image1.png", 100, 50), image.image)
        assertEquals(RectF(25f, 10f, 75f, 40f), image.src)
        assertEquals(RectF(0f, 0f, 200f, 100f), image.dst)
        assertEquals(0.5f, image.effects.alpha, 0f)
        assertNull(image.effects.clip)
        assertTrue(ops[3] is DrawOp.Restore)
    }

    @Test
    fun `a non-rectangular picture mask becomes the clip outline`() = runBlocking {
        val picture = picture(mask = ScenePreset("ellipse"))
        val resolver = SlidePictureResolver { SlideImage(File("image1.png"), ImageBounds(10, 10)) }

        val image = renderer.render(scene(picture), resolver)!!.ops
            .filterIsInstance<DrawOp.DrawImage>()
            .single()

        val clip = image.effects.clip
        assertNotNull(clip)
        assertTrue(clip!!.segments.size > 4)
    }

    @Test
    fun `nodes that cannot be drawn faithfully are dropped whole`() = runBlocking {
        val complex = ScenePaint.Complex(XNode(OoxmlName(OoxmlNamespace.A, "blipFill")), null)
        val effects = XNode(OoxmlName(OoxmlNamespace.A, "effectLst")).apply {
            children += XNode(OoxmlName(OoxmlNamespace.A, "outerShdw"))
        }
        val page = scene(
            rect(format = SceneFormat(complex, null, null)),
            rect(format = SceneFormat(ScenePaint.Solid(red), null, effects)),
            rect(preset = ScenePreset("notAPreset")),
            picture(source = PictureSource(fill = PictureFill.Tile(PictureTile())), id = 4),
            SceneConnector(
                id = 5,
                name = null,
                frame = SceneRect(0.0, 0.0, 100.0, 100.0),
                preset = ScenePreset("line"),
                format = SceneFormat(
                    ScenePaint.None,
                    SceneLine(widthEmu = 12_700.0, paint = ScenePaint.Solid(black), dash = null)
                        .withArrow(),
                    null,
                ),
            ),
        )

        val resolver = SlidePictureResolver { SlideImage(File("image1.png"), ImageBounds(10, 10)) }
        val list = renderer.render(page, resolver)

        assertNotNull(list)
        assertTrue(list!!.ops.isEmpty())
    }

    @Test
    fun `a spent time budget abandons the slide`() = runBlocking {
        val budgeted = SlideRenderer(presets, PptxLimits(maxSlideRenderMillis = 0))

        assertNull(budgeted.render(scene(rect())))
    }

    @Test
    fun `a cancelled caller stops the walk`() {
        val job = Job()
        var calls = 0
        val cancelled = SlideRenderer(
            presets,
            nowNanos = {
                calls++
                if (calls == 2) job.cancel()
                calls.toLong()
            },
        )

        assertThrows(CancellationException::class.java) {
            runBlocking(job) { cancelled.render(scene(rect(), rect(id = 2))) }
        }
    }

    private fun scene(vararg nodes: SceneNode, background: ScenePaint = ScenePaint.None) =
        Scene(SceneSize(400.0, 200.0), nodes.toList(), background)

    private fun rect(
        id: Int = 1,
        frame: SceneRect = SceneRect(10.0, 20.0, 30.0, 40.0),
        format: SceneFormat? = SceneFormat(ScenePaint.Solid(red), null, null),
        preset: ScenePreset? = ScenePreset("rect"),
        transform: XNode? = null,
    ) = SceneShape(
        id = id,
        name = null,
        frame = frame,
        preset = preset,
        transform = transform,
        format = format,
    )

    private fun picture(
        id: Int = 3,
        source: PictureSource = PictureSource(),
        mask: ScenePreset? = null,
    ) = ScenePicture(
        id = id,
        name = null,
        frame = SceneRect(10.0, 20.0, 200.0, 100.0),
        source = source,
        mask = mask,
        format = SceneFormat(ScenePaint.None, null, null),
    )

    private fun xfrm(vararg attributes: Pair<String, String>) = XNode(
        OoxmlName("", "xfrm"),
        attributes.map { (name, value) -> OoxmlAttribute("", name, value) },
    )

    private fun SceneLine.withArrow() = SceneLine(
        widthEmu = widthEmu,
        paint = paint,
        dash = dash,
        cap = cap,
        join = join,
        miterLimit = miterLimit,
        compound = compound,
        customDash = customDash,
        headEnd = io.github.pixelrick420.papyrus.viewer.pptx.ArrowEnd(io.github.pixelrick420.papyrus.viewer.pptx.ArrowType.TRIANGLE),
        tailEnd = tailEnd,
    )
}
