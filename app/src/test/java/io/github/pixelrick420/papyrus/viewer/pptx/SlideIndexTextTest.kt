package io.github.pixelrick420.papyrus.viewer.pptx

import io.github.pixelrick420.papyrus.viewer.Deck
import io.github.pixelrick420.papyrus.viewer.OfficeFixtures
import io.github.pixelrick420.papyrus.viewer.pptx.table.TableLayout
import io.github.pixelrick420.papyrus.viewer.pptx.text.AwtTextMeasurer
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextLayoutEngine
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextStyleResolver
import io.github.pixelrick420.papyrus.viewer.pptx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SlideIndexTextTest {

    @Test
    fun `index reads exactly the words find can highlight`() {
        val bytes = deck().toBytes()
        assertEquals(findPages(bytes), indexPages(bytes))
    }

    @Test
    fun `text find cannot highlight stays out of the index`() {
        val pages = indexPages(deck().toBytes())
        assertFalse(pages.any { "Quarterly revenue" in it })
        assertFalse(pages.any { "Jan Feb Mar" in it })
    }

    private fun indexPages(bytes: ByteArray): List<String> = SlideTextIndex.indexPages(
        open = { bytes.inputStream() },
        measurer = AwtTextMeasurer(),
        newParserFactory = OfficeFixtures::parser,
    )

    private fun findPages(bytes: ByteArray): List<String> {
        val parser = OfficeFixtures::parser
        val pkg = PptxPackage.read({ bytes.inputStream() }, mediaDir = null, newParserFactory = parser)
        val main = pkg.mainPart
        val presentation = PresentationParser(parser, PptxLimits()).parse(pkg.part(main)!!, main)
        val scenes = StyleResolver(parser, PptxLimits()).resolveAll(pkg, presentation)
        val resolver = TextStyleResolver()
        val engine = TextLayoutEngine(AwtTextMeasurer())
        for (slide in scenes.slides) TableLayout.layout(slide.scene, resolver, engine)
        return SlideTextIndex.fromScenes(scenes.slides, resolver, engine).pages.map { it.text }
    }

    private fun deck(): Deck = pptx {
        slide {
            title("Welcome")
            body {
                para("First point")
                para("Second point")
            }
        }
        slide {
            title("Data")
            table(0, 0, listOf(1000, 1000, 1000)) {
                row(400, "a", "b", "c")
                row(400, "d", "e", "f")
            }
        }
        slide {
            title("Chart slide")
            graphicFrame(CHART_URI, """<c:chart xmlns:c="$CHART_NS" r:id="rId2"/>""", 0, 0, 1000, 1000)
            rel("rId2", "$REL/chart", "../charts/chart1.xml")
        }
        part("ppt/charts/chart1.xml", CHART_XML.toByteArray())
    }
}

private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"

private const val CHART_NS = "http://schemas.openxmlformats.org/drawingml/2006/chart"

private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

private const val CHART_URI = "http://schemas.openxmlformats.org/drawingml/2006/chart"

private val CHART_XML = """
    <c:chartSpace xmlns:c="$CHART_NS" xmlns:a="$A_NS">
      <c:chart>
        <c:title>
          <c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>Quarterly revenue</a:t></a:r></a:p></c:rich></c:tx>
        </c:title>
        <c:plotArea>
          <c:barChart>
            <c:ser>
              <c:cat>
                <c:strRef>
                  <c:strCache>
                    <c:ptCount val="3"/>
                    <c:pt idx="0"><c:v>Jan</c:v></c:pt>
                    <c:pt idx="1"><c:v>Feb</c:v></c:pt>
                    <c:pt idx="2"><c:v>Mar</c:v></c:pt>
                  </c:strCache>
                </c:strRef>
              </c:cat>
            </c:ser>
          </c:barChart>
        </c:plotArea>
      </c:chart>
    </c:chartSpace>
""".trimIndent()
