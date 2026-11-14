package io.github.pixelrick420.papyrus.viewer

import io.github.pixelrick420.papyrus.viewer.pptx.SlideTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Test

class SlideTextExtractorTest {

    @Test
    fun `title and body read in shape-tree order`() {
        val deck = pptx {
            layout("Title and Content")
            slide {
                title("Welcome")
                body {
                    para("First point")
                    para("Second point")
                }
            }
        }
        assertEquals(listOf("Welcome\nFirst point\nSecond point"), texts(deck))
    }

    @Test
    fun `a title later in the tree is not moved to the front`() {
        val deck = pptx {
            slide {
                textBox(0, 0, 100, 100) { para("Intro") }
                title("Heading")
            }
        }
        assertEquals(listOf("Intro\nHeading"), texts(deck))
    }

    @Test
    fun `group children read in place`() {
        val deck = pptx {
            slide {
                textBox(0, 0, 100, 100) { para("Before") }
                group(0, 0, 200, 200) {
                    shape(Preset.RECT, 0, 0, 100, 100) {
                        text { para("Inside") }
                    }
                }
                textBox(0, 0, 100, 100) { para("After") }
            }
        }
        assertEquals(listOf("Before\nInside\nAfter"), texts(deck))
    }

    @Test
    fun `tables read row-wise with cells left to right`() {
        val deck = pptx {
            slide {
                title("Data")
                table(0, 0, listOf(1000, 1000, 1000)) {
                    row(400, "a", "b", "c")
                    row(400, "d", "e", "f")
                }
            }
        }
        assertEquals(listOf("Data\na b c\nd e f"), texts(deck))
    }

    @Test
    fun `merged table cells are read once`() {
        val deck = pptx {
            slide {
                table(0, 0, listOf(1000, 1000)) {
                    row(400) {
                        cell("wide", gridSpan = 2)
                    }
                    row(400, "left", "right")
                }
            }
        }
        assertEquals(listOf("wide\nleft right"), texts(deck))
    }

    @Test
    fun `hidden shapes and groups are skipped`() {
        val deck = pptx {
            slide {
                title("Shown")
                shape(Preset.RECT, 0, 0, 100, 100, hidden = true) {
                    text { para("Hidden shape") }
                }
                group(0, 0, 200, 200, hidden = true) {
                    shape(Preset.RECT, 0, 0, 100, 100) {
                        text { para("Hidden group") }
                    }
                }
                shape(Preset.RECT, 0, 0, 100, 100) {
                    text { para("Visible") }
                }
            }
        }
        assertEquals(listOf("Shown\nVisible"), texts(deck))
    }

    @Test
    fun `a slide with no text is still an entry`() {
        val deck = pptx { slide {} }
        assertEquals(listOf(""), texts(deck))
    }

    @Test
    fun `chart titles and category labels are read`() {
        val deck = pptx {
            slide {
                title("Chart slide")
                graphicFrame(CHART_URI, """<c:chart xmlns:c="$CHART_NS" r:id="rId2"/>""", 0, 0, 1000, 1000)
                rel("rId2", "$REL/chart", "../charts/chart1.xml")
            }
            part("ppt/charts/chart1.xml", CHART_XML.toByteArray())
        }
        assertEquals(listOf("Chart slide\nQuarterly revenue\nJan Feb Mar"), texts(deck))
    }

    @Test
    fun `smartart reads its cached drawing text`() {
        val deck = diagramDeck(withDrawing = true)
        assertEquals(listOf("Diagram slide\nCached alpha\nCached beta"), texts(deck))
    }

    @Test
    fun `smartart falls back to the data model when there is no drawing text`() {
        val deck = diagramDeck(withDrawing = false)
        assertEquals(listOf("Diagram slide\nModel alpha\nModel beta"), texts(deck))
    }

    @Test
    fun `a deck whose slides cannot be read yields an empty slide`() {
        val deck = pptx {
            slide { title("Broken") }
        }
        val broken = Deck(deck.parts + ("ppt/slides/slide1.xml" to "<not-a-slide".toByteArray()))
        assertEquals(
            listOf(""),
            SlideTextExtractor(OfficeFixtures::parser).extract(OfficeFixtures.streamFactory(broken.toBytes())),
        )
    }

    private fun diagramDeck(withDrawing: Boolean): Deck = pptx {
        slide {
            title("Diagram slide")
            graphicFrame(DIAGRAM_URI, """<dgm:relIds xmlns:dgm="$DIAGRAM_NS" r:dm="rId2"/>""", 0, 0, 1000, 1000)
            rel("rId2", "$REL/diagramData", "../diagrams/data1.xml")
        }
        part("ppt/diagrams/data1.xml", DIAGRAM_DATA_XML.toByteArray())
        if (withDrawing) {
            part("ppt/diagrams/drawing1.xml", DIAGRAM_DRAWING_XML.toByteArray())
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

    private fun texts(deck: Deck): List<String> =
        SlideTextExtractor(OfficeFixtures::parser).extract(OfficeFixtures.streamFactory(deck.toBytes()))
}

private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"

private const val CHART_NS = "http://schemas.openxmlformats.org/drawingml/2006/chart"

private const val DIAGRAM_NS = "http://schemas.openxmlformats.org/drawingml/2006/diagram"

private const val DRAWING_NS = "http://schemas.microsoft.com/office/drawing/2008/diagram"

private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

private const val REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"

private const val CHART_URI = "http://schemas.openxmlformats.org/drawingml/2006/chart"

private const val DIAGRAM_URI = "http://schemas.openxmlformats.org/drawingml/2006/diagram"

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

private val DIAGRAM_DRAWING_XML = """
    <dsp:drawing xmlns:dsp="$DRAWING_NS" xmlns:a="$A_NS">
      <dsp:spTree>
        <dsp:sp>
          <dsp:txBody>
            <a:bodyPr/><a:lstStyle/>
            <a:p><a:r><a:t>Cached alpha</a:t></a:r></a:p>
            <a:p><a:r><a:t>Cached beta</a:t></a:r></a:p>
          </dsp:txBody>
        </dsp:sp>
      </dsp:spTree>
    </dsp:drawing>
""".trimIndent()

private val DIAGRAM_DATA_XML = """
    <dgm:dataModel xmlns:dgm="$DIAGRAM_NS" xmlns:a="$A_NS">
      <dgm:ptLst>
        <dgm:pt modelId="1">
          <dgm:t><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>Model alpha</a:t></a:r></a:p></dgm:t>
        </dgm:pt>
        <dgm:pt modelId="2">
          <dgm:t><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>Model beta</a:t></a:r></a:p></dgm:t>
        </dgm:pt>
      </dgm:ptLst>
    </dgm:dataModel>
""".trimIndent()
