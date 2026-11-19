package io.github.pixelrick420.papyrus.viewer.pptx.render

import io.github.pixelrick420.papyrus.viewer.OfficeFixtures
import io.github.pixelrick420.papyrus.viewer.pptx.ArrowEnd
import io.github.pixelrick420.papyrus.viewer.pptx.ArrowType
import io.github.pixelrick420.papyrus.viewer.pptx.GradientFlip
import io.github.pixelrick420.papyrus.viewer.pptx.GradientStop
import io.github.pixelrick420.papyrus.viewer.pptx.GradientTile
import io.github.pixelrick420.papyrus.viewer.pptx.GraphicKind
import io.github.pixelrick420.papyrus.viewer.pptx.ImageBounds
import io.github.pixelrick420.papyrus.viewer.pptx.LinearGradient
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlAttribute
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlName
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlNamespace
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlReader
import io.github.pixelrick420.papyrus.viewer.pptx.PictureCrop
import io.github.pixelrick420.papyrus.viewer.pptx.PictureFill
import io.github.pixelrick420.papyrus.viewer.pptx.PictureInsets
import io.github.pixelrick420.papyrus.viewer.pptx.PictureSource
import io.github.pixelrick420.papyrus.viewer.pptx.PatternFills
import io.github.pixelrick420.papyrus.viewer.pptx.PictureTile
import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import io.github.pixelrick420.papyrus.viewer.pptx.PathGradient
import io.github.pixelrick420.papyrus.viewer.pptx.PresetGeometry
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.Scene
import io.github.pixelrick420.papyrus.viewer.pptx.SceneConnector
import io.github.pixelrick420.papyrus.viewer.pptx.SceneEffect
import io.github.pixelrick420.papyrus.viewer.pptx.SceneEffects
import io.github.pixelrick420.papyrus.viewer.pptx.SceneFormat
import io.github.pixelrick420.papyrus.viewer.pptx.SceneGraphicFrame
import io.github.pixelrick420.papyrus.viewer.pptx.SceneGroup
import io.github.pixelrick420.papyrus.viewer.pptx.SceneLine
import io.github.pixelrick420.papyrus.viewer.pptx.SceneNode
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePicture
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePreset
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import io.github.pixelrick420.papyrus.viewer.pptx.SceneShape
import io.github.pixelrick420.papyrus.viewer.pptx.SceneSize
import io.github.pixelrick420.papyrus.viewer.pptx.SceneTextFrame
import io.github.pixelrick420.papyrus.viewer.pptx.StrokeCompound
import io.github.pixelrick420.papyrus.viewer.pptx.XNode
import io.github.pixelrick420.papyrus.viewer.pptx.table.PptxTable
import io.github.pixelrick420.papyrus.viewer.pptx.table.TableCell
import io.github.pixelrick420.papyrus.viewer.pptx.table.TableCellProperties
import io.github.pixelrick420.papyrus.viewer.pptx.table.TableGrid
import io.github.pixelrick420.papyrus.viewer.pptx.table.TableRow
import io.github.pixelrick420.papyrus.viewer.pptx.text.AwtTextMeasurer
import io.github.pixelrick420.papyrus.viewer.pptx.text.LineBreaker
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextLayoutEngine
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextStyleResolver
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextStyleSources
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    private val textStyle = TextStyleResolver()

    private val textEngine = TextLayoutEngine(AwtTextMeasurer(), breaker = LineBreaker { _, _ -> emptyList() })

    private val textRenderer = SlideRenderer(presets, text = SlideTextPainter(textStyle, textEngine))

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
    fun `a rect path gradient fills the fillToRect region`() = runBlocking {
        val gradient = PathGradient(
            stops = listOf(GradientStop(0.0, red), GradientStop(1.0, black)),
            left = 0.25,
            top = 0.0,
            right = 0.25,
            bottom = 0.0,
            followsShape = false,
        )

        val paint = (renderer.render(scene(background = ScenePaint.Path(gradient)))!!.ops.single()
            as DrawOp.FillPath).paint as PaintSpec.Linear

        assertEquals(PointF(100f, 0f), paint.start)
        assertEquals(PointF(300f, 200f), paint.end)
        assertEquals(GradientTileMode.CLAMP, paint.tileMode)
    }

    @Test
    fun `a gradient that does not rotate with its shape is counter-rotated`() = runBlocking {
        val gradient = LinearGradient(
            angleDegrees = 0.0,
            scaled = true,
            stops = listOf(GradientStop(0.0, red), GradientStop(1.0, black)),
            rotatesWithShape = false,
        )
        val format = SceneFormat(ScenePaint.Linear(gradient), null, null)

        val ops = renderer.render(scene(rect(transform = xfrm("rot" to "5400000"), format = format)))!!.ops
        val paint = (ops[2] as DrawOp.FillPath).paint as PaintSpec.Linear

        assertEquals(PointF(15f, 35f), paint.start)
        assertEquals(PointF(15f, 5f), paint.end)
    }

    @Test
    fun `tileRect and flip choose the gradient shader tile mode`() = runBlocking {
        assertEquals(GradientTileMode.CLAMP, backgroundGradient(GradientTile()).tileMode)
        assertEquals(GradientTileMode.REPEAT, backgroundGradient(GradientTile(left = 0.1)).tileMode)
        assertEquals(GradientTileMode.MIRROR, backgroundGradient(GradientTile(flip = GradientFlip.X)).tileMode)
    }

    private suspend fun backgroundGradient(tile: GradientTile): PaintSpec.Linear {
        val gradient = LinearGradient(
            angleDegrees = 0.0,
            scaled = true,
            stops = listOf(GradientStop(0.0, red), GradientStop(1.0, black)),
            tile = tile,
        )
        return (renderer.render(scene(background = ScenePaint.Linear(gradient)))!!.ops.single()
            as DrawOp.FillPath).paint as PaintSpec.Linear
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
    fun `a negative crop pads the frame instead of dropping the picture`() = runBlocking {
        val picture = picture(source = PictureSource(crop = PictureCrop(left = -0.25, right = 0.25)))
        val resolver = SlidePictureResolver { SlideImage(File("image1.png"), ImageBounds(100, 50)) }

        val image = renderer.render(scene(picture), resolver)!!.ops
            .filterIsInstance<DrawOp.DrawImage>()
            .single()

        assertEquals(RectF(0f, 0f, 75f, 50f), image.src)
        assertEquals(RectF(50f, 0f, 200f, 100f), image.dst)
    }

    @Test
    fun `a negative fill rect inset draws the picture into an inset destination`() = runBlocking {
        val picture = picture(source = PictureSource(fill = PictureFill.Stretch(PictureInsets(left = -0.25))))
        val resolver = SlidePictureResolver { SlideImage(File("image1.png"), ImageBounds(100, 50)) }

        val image = renderer.render(scene(picture), resolver)!!.ops
            .filterIsInstance<DrawOp.DrawImage>()
            .single()

        assertEquals(RectF(20f, 0f, 100f, 50f), image.src)
        assertEquals(RectF(0f, 0f, 200f, 100f), image.dst)
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
    fun `a pattern fill becomes a repeating paint spec`() = runBlocking {
        val tile = PatternFills.tileOf("pct10")!!
        val paint = ScenePaint.Pattern(tile, red, black, 0.5)
        val ops = renderer.render(scene(rect(format = SceneFormat(paint, null, null))))!!.ops

        val spec = ops.filterIsInstance<DrawOp.FillPath>().single().paint as PaintSpec.Pattern
        assertSame(tile, spec.tile)
        assertEquals(red, spec.foreground)
        assertEquals(black, spec.background)
        assertEquals(0.5f, spec.scale)
    }

    @Test
    fun `a shadow becomes a blurred copy of the shape path`() = runBlocking {
        val shadow = SceneEffect.Shadow(
            color = red,
            blurEmu = 9_525.0,
            distanceEmu = 9_525.0,
            directionDegrees = 0.0,
            rotateWithShape = false,
        )
        val ops = renderer.render(
            scene(rect(format = SceneFormat(ScenePaint.Solid(black), null, SceneEffects(listOf(shadow))))),
        )!!.ops

        val emitted = ops.filterIsInstance<DrawOp.ShapeShadow>().single()
        assertEquals(red, emitted.shadow.color)
        assertEquals(1f, emitted.shadow.blur, 0f)
        assertEquals(1.0, emitted.shadow.transform.e, 0.0)
        assertEquals(0.0, emitted.shadow.transform.f, 0.0)
        assertTrue(ops.indexOf(emitted) < ops.indexOfFirst { it is DrawOp.FillPath })
    }

    @Test
    fun `glow and soft edge become blurred ops`() = runBlocking {
        val effects = SceneEffects(
            listOf(SceneEffect.Glow(red, 9_525.0), SceneEffect.SoftEdge(19_050.0)),
        )
        val ops = renderer.render(
            scene(rect(format = SceneFormat(ScenePaint.Solid(black), null, effects))),
        )!!.ops

        val glow = ops.filterIsInstance<DrawOp.Glow>().single()
        val edge = ops.filterIsInstance<DrawOp.SoftEdge>().single()
        assertEquals(red, glow.color)
        assertEquals(1f, glow.radius, 0f)
        assertEquals(2f, edge.radius, 0f)
    }

    @Test
    fun `blur becomes a blurred fill`() = runBlocking {
        val effects = SceneEffects(listOf(SceneEffect.Blur))
        val ops = renderer.render(
            scene(rect(format = SceneFormat(ScenePaint.Solid(black), null, effects))),
        )!!.ops

        assertEquals((50_800.0 / 9_525.0).toFloat(), ops.filterIsInstance<DrawOp.Blur>().single().radius, 0f)
    }

    @Test
    fun `a reflection becomes a mirrored paint with a fade`() = runBlocking {
        val reflection = SceneEffect.Reflection(
            blurEmu = 9_525.0,
            distanceEmu = 9_525.0,
            directionDegrees = 90.0,
            startAlpha = 0.8,
            endAlpha = 0.0,
            startPosition = 0.0,
            endPosition = 1.0,
        )
        val ops = renderer.render(
            scene(rect(format = SceneFormat(ScenePaint.Solid(black), null, SceneEffects(listOf(reflection))))),
        )!!.ops

        val emitted = ops.filterIsInstance<DrawOp.Reflection>().single().spec
        assertEquals(1f, emitted.blur, 0f)
        assertEquals(1f, emitted.distance, 0f)
        assertEquals(90f, emitted.directionDegrees, 0f)
        assertEquals(0.8f, emitted.startAlpha, 0f)
        assertEquals(0f, emitted.endAlpha, 0f)
    }

    @Test
    fun `an inner shadow stays clipped to the shape`() = runBlocking {
        val shadow = SceneEffect.Shadow(
            color = red,
            blurEmu = 9_525.0,
            distanceEmu = 0.0,
            directionDegrees = 0.0,
            inner = true,
        )
        val ops = renderer.render(
            scene(rect(format = SceneFormat(ScenePaint.Solid(black), null, SceneEffects(listOf(shadow))))),
        )!!.ops

        assertTrue(ops.filterIsInstance<DrawOp.ShapeShadow>().single().shadow.inner)
    }

    @Test
    fun `nodes that cannot be drawn faithfully are dropped whole`() = runBlocking {
        val complex = ScenePaint.Complex(XNode(OoxmlName(OoxmlNamespace.A, "blipFill")), null)
        val effects = SceneEffects(
            listOf(SceneEffect.Shadow(Rgba.BLACK, blurEmu = 0.0, distanceEmu = 0.0, directionDegrees = 0.0)),
        )
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
                    SceneLine(
                        widthEmu = 12_700.0,
                        paint = ScenePaint.Solid(black),
                        dash = null,
                        compound = StrokeCompound.DOUBLE,
                    ),
                    null,
                ),
            ),
        )

        val resolver = SlidePictureResolver { SlideImage(File("image1.png"), ImageBounds(10, 10)) }
        val list = renderer.render(page, resolver)

        val ops = list.ops
        assertTrue(ops.any { it is DrawOp.ShapeShadow })
        assertEquals(1, ops.count { it is DrawOp.FillPath })
    }

    @Test
    fun `an arrowed connector draws a trimmed shaft and a filled head`() = runBlocking {
        val line = SceneLine(
            widthEmu = 12_700.0,
            paint = ScenePaint.Solid(black),
            dash = null,
            tailEnd = ArrowEnd(ArrowType.TRIANGLE),
        )
        val ops = renderer.render(
            scene(
                SceneConnector(
                    id = 1,
                    name = null,
                    frame = SceneRect(0.0, 0.0, 1_000_000.0, 500_000.0),
                    preset = ScenePreset("line"),
                    format = SceneFormat(ScenePaint.None, line, null),
                ),
            ),
        )!!.ops

        assertEquals(1, ops.count { it is DrawOp.StrokePath })
        val head = ops.filterIsInstance<DrawOp.FillPath>().single()
        assertEquals(4, head.path.segments.size)
        assertEquals(PathSegment.Close, head.path.segments.last())
    }

    @Test
    fun `a spent time budget returns a partial slide`() = runBlocking {
        val budgeted = SlideRenderer(presets, PptxLimits(maxSlideRenderMillis = 0))

        val list = budgeted.render(scene(rect()))
        assertFalse(list.complete)
        assertTrue(list.ops.isNotEmpty())
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

    @Test
    fun `a shape's text is painted at its layout baseline`() = runBlocking {
        val body = textBody("Hello")
        val ops = textRenderer.render(scene(textShape()))!!.ops

        val index = ops.indexOfFirst { it is DrawOp.DrawGlyphRun }
        assertTrue(index > 1)
        assertTrue(ops[index - 2] is DrawOp.Save)
        val run = (ops[index] as DrawOp.DrawGlyphRun).run
        val resolved = textStyle.resolve(body)
        val layout = textEngine.layout(resolved, TEXT_FRAME.width, TEXT_FRAME.height)
        val expectedBaseline = resolved.properties.insets.top +
            layout.lines.first().runs.first().baselinePt * 12_700.0

        assertEquals("Hello", run.text)
        assertEquals(91_440f, run.x, 0.5f)
        assertEquals(expectedBaseline.toFloat(), run.y, 0.5f)
        assertEquals(228_600f, run.size, 0.5f)

        val map = (ops[index - 1] as DrawOp.Concat).matrix
        assertEquals(10.0, map.map(0.0, 0.0).x, 1e-9)
        assertEquals(20.0, map.map(0.0, 0.0).y, 1e-9)
    }

    @Test
    fun `text is painted for a shape with no fill or line`() = runBlocking {
        val ops = textRenderer.render(scene(textShape(format = null)))!!.ops

        assertEquals(1, ops.filterIsInstance<DrawOp.DrawGlyphRun>().size)
    }

    @Test
    fun `painting recovers inherited alignment and colour from the shape's text sources`() = runBlocking {
        val lstStyleXml = """<a:lstStyle $NAMESPACES><a:lvl1pPr algn="r"><a:defRPr sz="1800">""" +
            """<a:solidFill><a:srgbClr val="065A82"/></a:solidFill></a:defRPr></a:lvl1pPr></a:lstStyle>"""
        val lstStyle = OoxmlReader(OfficeFixtures::parser).readTree(
            lstStyleXml.toByteArray().inputStream(),
            "test.xml",
        )!!
        val inheritedBody = OoxmlReader(OfficeFixtures::parser).readTree(
            """<a:txBody $NAMESPACES><a:bodyPr/><a:p><a:r><a:t>Hello</a:t></a:r></a:p></a:txBody>"""
                .toByteArray().inputStream(),
            "test.xml",
        )!!
        val shape = SceneShape(
            id = 1,
            name = null,
            frame = TEXT_FRAME,
            text = SceneTextFrame(
                paragraphs = emptyList(),
                textSources = TextStyleSources(listStyles = listOf(lstStyle)),
            ),
            textBody = inheritedBody,
            format = null,
        )

        val run = textRenderer.render(scene(shape))!!.ops
            .filterIsInstance<DrawOp.DrawGlyphRun>().single().run
        val plainRun = textRenderer.render(scene(textShape()))!!.ops
            .filterIsInstance<DrawOp.DrawGlyphRun>().single().run

        assertEquals(PaintSpec.Solid(Rgba(0x06, 0x5A, 0x82)), run.paint)
        assertTrue("inherited right alignment should move the run right", run.x > plainRun.x + 12_700f)
    }

    @Test
    fun `table layout is computed on the scene being rendered`() = runBlocking {
        val table = PptxTable(
            grid = TableGrid(listOf(100_000.0)),
            rows = listOf(
                TableRow(
                    50_000.0,
                    listOf(TableCell(textBody = null, properties = TableCellProperties(fill = ScenePaint.Solid(red)))),
                ),
            ),
        )
        val frame = SceneGraphicFrame(
            id = 2,
            name = "table",
            frame = SceneRect(0.0, 0.0, 100_000.0, 50_000.0),
            kind = GraphicKind.TABLE,
            table = table,
        )
        assertNull(table.layout)

        val ops = textRenderer.render(scene(frame))!!.ops

        assertNotNull(table.layout)
        assertTrue(ops.any { it is DrawOp.FillPath && it.paint == PaintSpec.Solid(red) })
    }

    private fun textBody(text: String): XNode = OoxmlReader(OfficeFixtures::parser).readTree(
        """<a:txBody $NAMESPACES><a:bodyPr/><a:p><a:r><a:rPr sz="1800"/><a:t>$text</a:t></a:r></a:p></a:txBody>"""
            .toByteArray().inputStream(),
        "test.xml",
    )!!

    private val NAMESPACES =
        """xmlns:a="${OoxmlNamespace.A}" xmlns:p="${OoxmlNamespace.P}" xmlns:r="${OoxmlNamespace.R}""""

    private fun textShape(format: SceneFormat? = SceneFormat(ScenePaint.Solid(red), null, null)) = SceneShape(
        id = 1,
        name = null,
        frame = TEXT_FRAME,
        preset = ScenePreset("rect"),
        textBody = textBody("Hello"),
        format = format,
    )

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

    private companion object {
        /** 300 × 100 points, in EMU, as a text layout needs a frame wider than its insets. */
        val TEXT_FRAME = SceneRect(10.0, 20.0, 300.0 * 12_700.0, 100.0 * 12_700.0)
    }
}
