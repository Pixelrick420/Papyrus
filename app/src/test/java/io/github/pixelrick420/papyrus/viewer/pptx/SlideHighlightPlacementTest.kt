package io.github.pixelrick420.papyrus.viewer.pptx

import io.github.pixelrick420.papyrus.viewer.OfficeFixtures
import io.github.pixelrick420.papyrus.viewer.pptx.render.DrawOp
import io.github.pixelrick420.papyrus.viewer.pptx.render.GlyphRun
import io.github.pixelrick420.papyrus.viewer.pptx.render.SlideRenderer
import io.github.pixelrick420.papyrus.viewer.pptx.render.SlideTextPainter
import io.github.pixelrick420.papyrus.viewer.pptx.table.PptxTable
import io.github.pixelrick420.papyrus.viewer.pptx.table.TableCell
import io.github.pixelrick420.papyrus.viewer.pptx.table.TableCellAnchor
import io.github.pixelrick420.papyrus.viewer.pptx.table.TableCellProperties
import io.github.pixelrick420.papyrus.viewer.pptx.table.TableGrid
import io.github.pixelrick420.papyrus.viewer.pptx.table.TableInsets
import io.github.pixelrick420.papyrus.viewer.pptx.table.TableLayout
import io.github.pixelrick420.papyrus.viewer.pptx.table.TableRow
import io.github.pixelrick420.papyrus.viewer.pptx.text.AwtTextMeasurer
import io.github.pixelrick420.papyrus.viewer.pptx.text.LineBreaker
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextLayoutEngine
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextStyleResolver
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextStyleSources
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File
import java.util.ArrayDeque

class SlideHighlightPlacementTest {

    @Test
    fun `selection rect matches painted text with large insets`() {
        val body = textBody(
            "Padded",
            bodyAttrs = """ lIns="914400" tIns="914400" rIns="914400" bIns="914400"""",
        )
        val shape = SceneShape(
            id = 1,
            name = null,
            frame = SceneRect(500_000.0, 400_000.0, 3_000_000.0, 2_000_000.0),
            textBody = body,
        )
        assertAligned(sceneOf(shape), "Padded")
    }

    @Test
    fun `selection rect matches painted text sized by inherited style`() {
        val style = readXml(
            """<a:lstStyle $NAMESPACES><a:lvl1pPr><a:defRPr sz="4400"/></a:lvl1pPr></a:lstStyle>""",
        )
        val shape = SceneShape(
            id = 1,
            name = null,
            frame = SceneRect(500_000.0, 400_000.0, 3_000_000.0, 2_000_000.0),
            text = SceneTextFrame(
                paragraphs = emptyList(),
                textSources = TextStyleSources(listStyles = listOf(style)),
            ),
            textBody = textBody("Inherited"),
        )
        assertAligned(sceneOf(shape), "Inherited")
    }

    @Test
    fun `selection rect matches painted text in a middle anchored table cell`() {
        val cell = TableCell(
            textBody = textBody("Centered"),
            properties = TableCellProperties(
                margins = TableInsets(left = 457_200.0, top = 228_600.0, right = 457_200.0, bottom = 228_600.0),
                anchor = TableCellAnchor.MIDDLE,
            ),
        )
        val frame = SceneGraphicFrame(
            id = 1,
            name = null,
            frame = SceneRect(500_000.0, 400_000.0, 3_000_000.0, 2_000_000.0),
            kind = GraphicKind.TABLE,
            table = PptxTable(
                grid = TableGrid(listOf(3_000_000.0)),
                rows = listOf(TableRow(2_000_000.0, listOf(cell))),
            ),
        )
        assertAligned(sceneOf(frame), "Centered")
    }

    private fun assertAligned(scene: Scene, text: String) {
        val resolver = TextStyleResolver()
        val engine = TextLayoutEngine(AwtTextMeasurer(), breaker = LineBreaker { _, _ -> emptyList() })
        TableLayout.layout(scene, resolver, engine)
        val page = SlideTextIndex.fromScenes(
            listOf(SlideScene("slide.xml", scene, false)),
            resolver,
            engine,
        ).pages.single()

        val renderer = SlideRenderer(
            PresetGeometry(
                open = { File("src/main/assets/ooxml/presetShapeDefinitions.xml").inputStream() },
                newParserFactory = { KXmlParser() },
            ),
            text = SlideTextPainter(resolver, engine),
        )
        val (glyph, transform) = paintedRun(runBlocking { renderer.render(scene)!!.ops }, text)
        val start = transform.map(glyph.x.toDouble(), glyph.y.toDouble())

        val at = page.text.indexOf(text)
        assertTrue("index text was ${page.text}", at >= 0)
        val rect = page.selectionRects(at, at + text.length).single()
        assertEquals(
            "highlight must start where the glyph starts",
            (start.x / scene.size.width).toFloat(),
            rect.left,
            0.005f,
        )
        val baseline = (start.y / scene.size.height).toFloat()
        assertTrue("highlight top ${rect.top} must be at or above baseline $baseline", rect.top <= baseline)
        assertTrue("highlight bottom ${rect.bottom} must be at or below baseline $baseline", rect.bottom >= baseline)
    }

    private fun paintedRun(ops: List<DrawOp>, text: String): Pair<GlyphRun, Affine> {
        val stack = ArrayDeque<Affine>()
        var transform = Affine.IDENTITY
        val runs = ArrayList<Pair<GlyphRun, Affine>>()
        for (op in ops) {
            when (op) {
                is DrawOp.Save -> stack.addLast(transform)
                is DrawOp.Concat -> transform = transform.then(op.matrix)
                is DrawOp.Restore -> if (stack.isNotEmpty()) transform = stack.removeLast()
                is DrawOp.DrawGlyphRun -> runs += op.run to transform
                else -> Unit
            }
        }
        return runs.first { it.first.text == text }
    }

    private fun sceneOf(vararg nodes: SceneNode) =
        Scene(SceneSize(4_000_000.0, 3_000_000.0), nodes.toList())

    private fun textBody(text: String, bodyAttrs: String = ""): XNode = readXml(
        """<a:txBody $NAMESPACES><a:bodyPr$bodyAttrs/><a:lstStyle/>""" +
            """<a:p><a:r><a:rPr sz="1800"/><a:t>$text</a:t></a:r></a:p></a:txBody>""",
    )

    private fun readXml(xml: String): XNode = OoxmlReader(OfficeFixtures::parser)
        .readTree(xml.toByteArray().inputStream(), "test.xml")!!

    private val NAMESPACES =
        """xmlns:a="${OoxmlNamespace.A}" xmlns:p="${OoxmlNamespace.P}" xmlns:r="${OoxmlNamespace.R}""""
}
