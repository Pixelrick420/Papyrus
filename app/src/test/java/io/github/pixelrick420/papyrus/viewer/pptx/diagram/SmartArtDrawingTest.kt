package io.github.pixelrick420.papyrus.viewer.pptx.diagram

import io.github.pixelrick420.papyrus.viewer.Deck
import io.github.pixelrick420.papyrus.viewer.OfficeFixtures
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlNamespace
import io.github.pixelrick420.papyrus.viewer.pptx.PptxPackage
import io.github.pixelrick420.papyrus.viewer.pptx.PresentationParser
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.Scene
import io.github.pixelrick420.papyrus.viewer.pptx.SceneGraphicFrame
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import io.github.pixelrick420.papyrus.viewer.pptx.SceneShape
import io.github.pixelrick420.papyrus.viewer.pptx.SlideTextIndex
import io.github.pixelrick420.papyrus.viewer.pptx.StyleResolver
import io.github.pixelrick420.papyrus.viewer.pptx.XNode
import io.github.pixelrick420.papyrus.viewer.pptx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartArtDrawingTest {

    private fun deck(
        drawing: String? = null,
        data: String = DATA_XML,
        description: String = "",
    ): Deck = pptx {
        slide {
            graphicFrame(
                uri = DIAGRAM_URI,
                content = """<dgm:relIds xmlns:dgm="$DIAGRAM_NS" r:dm="rId2"/>""",
                x = FRAME_X,
                y = FRAME_Y,
                w = FRAME_W,
                h = FRAME_H,
                name = "smartart",
                description = description,
            )
            rel("rId2", "$REL/diagramData", "../diagrams/data1.xml")
        }
        part("ppt/diagrams/data1.xml", data.toByteArray())
        if (drawing != null) {
            part("ppt/diagrams/drawing1.xml", drawing.toByteArray())
            part(
                "ppt/diagrams/_rels/data1.xml.rels",
                (
                    """<?xml version="1.0" encoding="UTF-8"?>""" +
                        """<Relationships xmlns="$REL_NS"><Relationship Id="rId1" Type="$REL/diagramDrawing" """ +
                        """Target="drawing1.xml"/></Relationships>"""
                    ).toByteArray(),
            )
        }
    }

    private fun sceneOf(deck: Deck): Scene {
        val bytes = deck.toBytes()
        val pkg = PptxPackage.read({ bytes.inputStream() }, newParserFactory = OfficeFixtures::parser)
        val presentation = PresentationParser(OfficeFixtures::parser).parse(
            deck.parts.getValue(pkg.mainPart),
            pkg.mainPart,
        )
        return StyleResolver(OfficeFixtures::parser).resolveAll(pkg, presentation).slides.single().scene
    }

    private fun smartArt(drawing: String): SmartArtDrawing {
        val frame = sceneOf(deck(drawing)).nodes.filterIsInstance<SceneGraphicFrame>().single { it.name == "smartart" }
        return frame.smartArt ?: error("the cached drawing was not resolved")
    }

    @Test
    fun `cached dsp shapes become scene shapes in the frame`() {
        val art = smartArt(cachedDrawing())

        val shapes = art.nodes.filterIsInstance<SceneShape>()
        assertEquals(2, shapes.size)
        assertEquals(SceneRect(FRAME_X.toDouble(), FRAME_Y.toDouble(), 200_000.0, 100_000.0), shapes[0].frame)
        assertEquals(SceneRect(FRAME_X + 250_000.0, FRAME_Y.toDouble(), 200_000.0, 100_000.0), shapes[1].frame)
        assertEquals("rect", shapes[0].preset?.preset)
    }

    @Test
    fun `dsp shape formatting is resolved into the scene format`() {
        val art = smartArt(cachedDrawing())

        val fill = art.nodes.filterIsInstance<SceneShape>().first().format?.fill
        assertEquals(Rgba(0xC0, 0x00, 0x00), (fill as? ScenePaint.Solid)?.color)
    }

    @Test
    fun `cached dsp text bodies are ready for the text engine`() {
        val art = smartArt(cachedDrawing())

        assertEquals(listOf("Alpha", "Beta"), art.texts.map { it.body.text() })
        assertNotNull(art.texts.first().frame)
        assertTrue(art.nodes.filterIsInstance<SceneShape>().all { it.textBody != null })
    }

    @Test
    fun `the separate dsp txXfrm places the text box`() {
        val art = smartArt(cachedDrawingWithTextXfrm())

        val shape = art.nodes.filterIsInstance<SceneShape>().single()
        assertEquals(SceneRect(FRAME_X.toDouble(), FRAME_Y.toDouble(), 200_000.0, 100_000.0), shape.frame)
        assertEquals(
            SceneRect(FRAME_X + 10_000.0, FRAME_Y + 20_000.0, 50_000.0, 30_000.0),
            art.texts.single().frame,
        )
    }

    @Test
    fun `a drawing with no sp tree is not resolved`() {
        val scene = sceneOf(deck("""<dsp:drawing xmlns:dsp="$DSP_NS"/>"""))
        val frame = scene.nodes.filterIsInstance<SceneGraphicFrame>().single { it.name == "smartart" }
        assertEquals(null, frame.smartArt)
    }

    @Test
    fun `a missing drawing part falls back to a labelled placeholder with node text`() {
        val scene = sceneOf(deck(data = DATA_WITH_NODES, description = "Diagram"))

        val frame = scene.nodes.filterIsInstance<SceneGraphicFrame>().single { it.name == "smartart" }
        val art = frame.smartArt ?: error("the fallback was not resolved")
        assertEquals(listOf("First node", "Second node"), art.nodeText)
        assertEquals(SceneRect(FRAME_X.toDouble(), FRAME_Y.toDouble(), FRAME_W.toDouble(), FRAME_H.toDouble()),
            art.nodes.single().frame)
        val label = art.texts.single().body.text()
        assertTrue(label.startsWith("SmartArt not displayed"))
        assertTrue("First node" in label)
        assertTrue("Generated node" !in label)

        val semantic = SlideTextIndex.semanticTextOf(scene)
        assertTrue("First node" in semantic)
        assertTrue("Generated node" !in semantic)
    }

    private fun XNode.text(): String = buildString {
        if (name.matches(OoxmlNamespace.A, "t")) append(text)
        for (child in children) append(child.text())
    }

    private fun cachedDrawing(): String = """
        <dsp:drawing xmlns:dsp="$DSP_NS" xmlns:a="$A_NS">
          <dsp:spTree>
            <dsp:sp modelId="{alpha}">
              <dsp:nvSpPr><dsp:cNvPr id="2" name="alpha"/></dsp:nvSpPr>
              <dsp:spPr>
                <a:xfrm><a:off x="0" y="0"/><a:ext cx="200000" cy="100000"/></a:xfrm>
                <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
                <a:solidFill><a:srgbClr val="C00000"/></a:solidFill>
              </dsp:spPr>
              <dsp:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>Alpha</a:t></a:r></a:p></dsp:txBody>
            </dsp:sp>
            <dsp:sp modelId="{beta}">
              <dsp:nvSpPr><dsp:cNvPr id="3" name="beta"/></dsp:nvSpPr>
              <dsp:spPr>
                <a:xfrm><a:off x="250000" y="0"/><a:ext cx="200000" cy="100000"/></a:xfrm>
                <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
                <a:solidFill><a:srgbClr val="00B050"/></a:solidFill>
              </dsp:spPr>
              <dsp:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>Beta</a:t></a:r></a:p></dsp:txBody>
            </dsp:sp>
          </dsp:spTree>
        </dsp:drawing>
    """.trimIndent()

    private fun cachedDrawingWithTextXfrm(): String = """
        <dsp:drawing xmlns:dsp="$DSP_NS" xmlns:a="$A_NS">
          <dsp:spTree>
            <dsp:sp modelId="{generated}">
              <dsp:nvSpPr><dsp:cNvPr id="2" name="alpha"/></dsp:nvSpPr>
              <dsp:spPr>
                <a:xfrm><a:off x="0" y="0"/><a:ext cx="200000" cy="100000"/></a:xfrm>
                <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
              </dsp:spPr>
              <dsp:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>Alpha</a:t></a:r></a:p></dsp:txBody>
              <dsp:txXfrm><a:off x="10000" y="20000"/><a:ext cx="50000" cy="30000"/></dsp:txXfrm>
            </dsp:sp>
          </dsp:spTree>
        </dsp:drawing>
    """.trimIndent()

    private companion object {
        const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
        const val DSP_NS = "http://schemas.microsoft.com/office/drawing/2008/diagram"
        const val DIAGRAM_NS = "http://schemas.openxmlformats.org/drawingml/2006/diagram"
        const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
        const val DIAGRAM_URI = "http://schemas.openxmlformats.org/drawingml/2006/diagram"
        const val FRAME_X = 1000L
        const val FRAME_Y = 2000L
        const val FRAME_W = 1_000_000L
        const val FRAME_H = 500_000L
        val DATA_XML: String get() = """<dgm:dataModel xmlns:dgm="$DIAGRAM_NS"/>"""

        val DATA_WITH_NODES: String get() = """
            <dgm:dataModel xmlns:dgm="$DIAGRAM_NS" xmlns:a="$A_NS">
              <dgm:ptLst>
                <dgm:pt modelId="1"><dgm:t><a:p><a:r><a:t>First node</a:t></a:r></a:p></dgm:t></dgm:pt>
                <dgm:pt modelId="2" type="pres"><dgm:t><a:p><a:r><a:t>Generated node</a:t></a:r></a:p></dgm:t></dgm:pt>
                <dgm:pt modelId="3"><dgm:t><a:p><a:r><a:t>Second node</a:t></a:r></a:p></dgm:t></dgm:pt>
              </dgm:ptLst>
            </dgm:dataModel>
        """.trimIndent()
    }
}
