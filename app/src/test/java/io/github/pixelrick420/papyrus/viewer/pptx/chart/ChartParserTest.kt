package io.github.pixelrick420.papyrus.viewer.pptx.chart

import io.github.pixelrick420.papyrus.viewer.OfficeFixtures
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlReader
import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartParserTest {

    @Test
    fun `parses series axes scaling and formatted tick labels`() {
        val xml = """
            <c:chartSpace xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart"
                          xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
              <c:chart>
                <c:title>
                  <c:tx><c:strRef><c:strCache><c:ptCount val="1"/><c:pt idx="0"><c:v>Sales</c:v></c:pt></c:strCache></c:strRef></c:tx>
                </c:title>
                <c:plotArea>
                  <c:layout><c:manualLayout><c:xMode val="factor"/><c:yMode val="factor"/><c:wMode val="factor"/><c:hMode val="factor"/><c:x val="0.1"/><c:y val="0.2"/><c:w val="0.7"/><c:h val="0.6"/></c:manualLayout></c:layout>
                  <c:barChart>
                    <c:barDir val="col"/>
                    <c:grouping val="clustered"/>
                    <c:ser>
                      <c:idx val="0"/>
                      <c:order val="0"/>
                      <c:tx><c:strRef><c:strCache><c:ptCount val="1"/><c:pt idx="0"><c:v>Revenue</c:v></c:pt></c:strCache></c:strRef></c:tx>
                      <c:cat>
                        <c:strRef>
                          <c:strCache>
                            <c:ptCount val="2"/>
                            <c:pt idx="0"><c:v>Jan</c:v></c:pt>
                            <c:pt idx="1"><c:v>Feb</c:v></c:pt>
                          </c:strCache>
                        </c:strRef>
                      </c:cat>
                      <c:val>
                        <c:numRef>
                          <c:numCache>
                            <c:formatCode>0%</c:formatCode>
                            <c:ptCount val="2"/>
                            <c:pt idx="0"><c:v>0.25</c:v></c:pt>
                            <c:pt idx="1"><c:v>0.5</c:v></c:pt>
                          </c:numCache>
                        </c:numRef>
                      </c:val>
                    </c:ser>
                  </c:barChart>
                  <c:catAx>
                    <c:axId val="1"/>
                    <c:scaling><c:orientation val="minMax"/></c:scaling>
                    <c:axPos val="b"/>
                    <c:majorTickMark val="out"/>
                    <c:tickLblPos val="nextTo"/>
                    <c:crossAx val="2"/>
                    <c:crosses val="autoZero"/>
                  </c:catAx>
                  <c:valAx>
                    <c:axId val="2"/>
                    <c:scaling><c:orientation val="minMax"/><c:min val="0"/><c:max val="1"/><c:majorUnit val="0.25"/></c:scaling>
                    <c:axPos val="l"/>
                    <c:majorGridlines/>
                    <c:numFmt formatCode="0%" sourceLinked="0"/>
                    <c:majorTickMark val="cross"/>
                    <c:tickLblPos val="nextTo"/>
                    <c:crossAx val="1"/>
                    <c:crosses val="autoZero"/>
                  </c:valAx>
                </c:plotArea>
                <c:legend><c:legendPos val="r"/><c:overlay val="0"/></c:legend>
              </c:chart>
            </c:chartSpace>
        """.trimIndent()

        val root = OoxmlReader(OfficeFixtures::parser).readTree(xml.byteInputStream())!!
        val chart = ChartParser.parse(root, PptxLimits())
        assertNotNull(chart)
        chart!!

        assertEquals("Sales", chart.title?.text)
        assertEquals(1, chart.plotArea.series.size)
        val series = chart.plotArea.series.single()
        assertEquals("Revenue", series.name)
        assertEquals(ChartType.COLUMN, series.type)
        assertEquals(listOf("Jan", "Feb"), series.categories?.points?.map { it.text })
        assertEquals(listOf(0.25, 0.5), series.values?.points?.map { it.number })

        assertEquals(2, chart.plotArea.axes.size)
        val valueAxis = chart.plotArea.axes.first { it.kind == AxisKind.VALUE }
        assertEquals(0.0, valueAxis.scaling.min!!, 0.0)
        assertEquals(1.0, valueAxis.scaling.max!!, 0.0)
        assertEquals(0.25, valueAxis.scaling.majorUnit!!, 0.0)
        assertEquals(AxisOrientation.MIN_MAX, valueAxis.scaling.orientation)
        assertEquals(TickMark.CROSS, valueAxis.majorTickMark)
        assertNotNull(valueAxis.majorGridlines)
        assertEquals("25%", ChartNumberFormats.formatTick(0.25, valueAxis))

        assertEquals(LegendPosition.RIGHT, chart.legend?.position)
        val layout = ChartLayout.layout(chart, SceneRect(0.0, 0.0, 1000.0, 800.0))
        assertTrue(layout.plotArea.width > 0.0)
        assertTrue(layout.plotArea.height > 0.0)
        assertNotNull(layout.legend)
        assertNotNull(layout.title)
    }
}
