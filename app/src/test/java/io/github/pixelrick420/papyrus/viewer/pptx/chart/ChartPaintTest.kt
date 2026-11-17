package io.github.pixelrick420.papyrus.viewer.pptx.chart

import io.github.pixelrick420.papyrus.viewer.Deck
import io.github.pixelrick420.papyrus.viewer.OfficeFixtures
import io.github.pixelrick420.papyrus.viewer.pptx.Affine
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlNamespace
import io.github.pixelrick420.papyrus.viewer.pptx.PptxPackage
import io.github.pixelrick420.papyrus.viewer.pptx.PresentationParser
import io.github.pixelrick420.papyrus.viewer.pptx.PresetGeometry
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.Scene
import io.github.pixelrick420.papyrus.viewer.pptx.SceneGraphicFrame
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import io.github.pixelrick420.papyrus.viewer.pptx.StyleResolver
import io.github.pixelrick420.papyrus.viewer.pptx.XNode
import io.github.pixelrick420.papyrus.viewer.pptx.render.DrawOp
import io.github.pixelrick420.papyrus.viewer.pptx.render.SlideRenderer
import io.github.pixelrick420.papyrus.viewer.pptx
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.File

class ChartPaintTest {

    private val presets = PresetGeometry(
        open = { File("src/main/assets/ooxml/presetShapeDefinitions.xml").inputStream() },
        newParserFactory = { KXmlParser() },
    )

    private fun deck(
        chart: String,
        width: Long = CHART_WIDTH,
        height: Long = CHART_HEIGHT,
        description: String = "",
        content: String? = null,
    ): Deck = pptx {
        theme {
            accent1("C00000")
            accent2("00B050")
        }
        layout("Blank")
        slide {
            graphicFrame(
                uri = CHART_URI,
                content = content ?: """<c:chart xmlns:c="$CHART_NS" r:id="rId2"/>""",
                x = CHART_X,
                y = CHART_Y,
                w = width,
                h = height,
                name = "chart",
                description = description,
            )
            rel("rId2", CHART_RELATIONSHIP, "../charts/chart1.xml")
            rel("rId3", "$R_NS/image", "../media/fallback.png")
        }
        part("ppt/charts/chart1.xml", chart.toByteArray())
        part("ppt/media/fallback.png", OfficeFixtures.PNG_1X1)
    }

    private fun scene(deck: Deck): Scene {
        val pkg = PptxPackage.read({ deck.toBytes().inputStream() }, newParserFactory = OfficeFixtures::parser)
        val presentation = PresentationParser(OfficeFixtures::parser)
            .parse(deck.parts.getValue("ppt/presentation.xml"))
        return StyleResolver(OfficeFixtures::parser).resolveAll(pkg, presentation).slides.single().scene
    }

    private fun drawing(deck: Deck): ChartDrawing {
        val frame = scene(deck).nodes.filterIsInstance<SceneGraphicFrame>().single { it.name == "chart" }
        return frame.chart ?: error("the chart was not resolved")
    }

    private class Bar(val rect: SceneRect, val fill: ScenePaint?)

    private fun bars(drawing: ChartDrawing): List<Bar> = drawing.shapes
        .filter { it.points.size == 4 && it.fill is ScenePaint.Solid }
        .map { Bar(rectangle(it.points), it.fill) }

    private fun texts(drawing: ChartDrawing): List<String> = drawing.texts.map { it.body.text() }

    private fun XNode.text(): String = buildString {
        if (name.matches(OoxmlNamespace.A, "t")) append(text)
        for (child in children) append(child.text())
    }

    @Test
    fun `clustered columns paint one bar per value in the series colour`() {
        val chart = columnChart(
            dir = "col",
            grouping = "clustered",
            series = listOf(
                "Alpha" to listOf(10.0, 20.0),
                "Beta" to listOf(30.0, 10.0),
            ),
        )

        val bars = bars(drawing(deck(chart)))
        assertEquals(4, bars.size)

        val colors = bars.map { (it.fill as ScenePaint.Solid).color }
        assertEquals(
            listOf(Rgba(0xC0, 0x00, 0x00), Rgba(0xC0, 0x00, 0x00), Rgba(0x00, 0xB0, 0x50), Rgba(0x00, 0xB0, 0x50)),
            colors,
        )

        val first = bars[0]
        val tallest = bars[2]
        assertEquals(1.0 / 3.0, first.rect.height / tallest.rect.height, 1e-6)
        assertTrue("the second series' bar starts to the right", bars[2].rect.x > bars[0].rect.x)
        assertEquals(bars[0].rect.width, bars[2].rect.width, 1e-6)
    }

    @Test
    fun `stacked columns share a baseline and grow by their own value`() {
        val chart = columnChart(
            dir = "col",
            grouping = "stacked",
            series = listOf(
                "Alpha" to listOf(10.0, 20.0),
                "Beta" to listOf(30.0, 10.0),
            ),
        )

        val bars = bars(drawing(deck(chart)))
        val base = bars[0].rect
        val next = bars[2].rect
        assertEquals(base.x, next.x, 1e-6)
        assertEquals(base.width, next.width, 1e-6)
        assertEquals(base.y, next.bottom, 1e-6)
        assertEquals(3.0, next.height / base.height, 1e-6)
    }

    @Test
    fun `percent stacked columns normalise every category to the full axis`() {
        val chart = columnChart(
            dir = "col",
            grouping = "percentStacked",
            series = listOf(
                "Alpha" to listOf(1.0, 2.0),
                "Beta" to listOf(3.0, 1.0),
            ),
        )

        val bars = bars(drawing(deck(chart)))
        val stack = bars[0].rect.height + bars[2].rect.height
        assertEquals(0.25, bars[0].rect.height / stack, 1e-6)
        assertEquals(0.75, bars[2].rect.height / stack, 1e-6)
        assertEquals(bars[0].rect.height + bars[2].rect.height, bars[1].rect.height + bars[3].rect.height, 1e-6)
    }

    @Test
    fun `horizontal bars grow along the value axis with the category height`() {
        val chart = columnChart(
            dir = "bar",
            grouping = "clustered",
            series = listOf(
                "Alpha" to listOf(10.0, 20.0),
                "Beta" to listOf(30.0, 10.0),
            ),
        )

        val bars = bars(drawing(deck(chart)))
        assertEquals(4, bars.size)
        assertEquals(bars[0].rect.height, bars[2].rect.height, 1e-6)
        assertTrue("the shorter bar is narrower", bars[0].rect.width < bars[2].rect.width)
        assertEquals(1.0 / 3.0, bars[0].rect.width / bars[2].rect.width, 1e-6)
    }

    @Test
    fun `chart text carries the title, ticks and legend names`() {
        val chart = columnChart(
            dir = "col",
            grouping = "clustered",
            series = listOf(
                "Alpha" to listOf(10.0, 20.0),
                "Beta" to listOf(30.0, 10.0),
            ),
            title = "Sales",
            legend = true,
        )

        val texts = texts(drawing(deck(chart)))
        assertTrue("no title in $texts", "Sales" in texts)
        assertTrue("no categories in $texts", listOf("A", "B").all { it in texts })
        assertTrue("no value ticks in $texts", listOf("0", "10", "20", "30").all { it in texts })
        assertTrue("no legend in $texts", listOf("Alpha", "Beta").all { it in texts })
    }

    @Test
    fun `the chart is painted at the graphic frame's origin`() = runBlocking {
        val chart = columnChart(
            dir = "col",
            grouping = "clustered",
            series = listOf("Alpha" to listOf(10.0, 20.0)),
        )

        val ops = SlideRenderer(presets).render(scene(deck(chart)))!!.ops
        val translateIndex = ops.indexOfFirst { it is DrawOp.Concat && (it.matrix).isTranslation() }
        assertTrue("no frame translation in $ops", translateIndex >= 0)
        val translate = ops[translateIndex] as DrawOp.Concat
        assertEquals(CHART_X.toDouble(), translate.matrix.e, 1e-6)
        assertEquals(CHART_Y.toDouble(), translate.matrix.f, 1e-6)
        val painted = ops.drop(translateIndex).takeWhile { it !is DrawOp.Restore }
        assertEquals(2, painted.count { it is DrawOp.FillPath })
        assertNotNull(ops.last() as? DrawOp.Restore)
    }

    @Test
    fun `line series paint a polyline and markers in the series colour`() {
        val drawing = drawing(
            deck(
                lineChart(
                    listOf(
                        "Alpha" to listOf(10.0, 20.0, 30.0),
                        "Beta" to listOf(20.0, 10.0, 5.0),
                    ),
                ),
            ),
        )

        val polylines = drawing.shapes.filter { !it.closed && it.fill == null && it.points.size == 3 }
        assertEquals(2, polylines.size)
        assertEquals(Rgba(0xC0, 0x00, 0x00), (polylines[0].line?.paint as ScenePaint.Solid).color)
        assertEquals(Rgba(0x00, 0xB0, 0x50), (polylines[1].line?.paint as ScenePaint.Solid).color)

        val markers = drawing.shapes.filter { it.points.size == 12 }
        assertEquals(6, markers.size)
        assertEquals(Rgba(0xC0, 0x00, 0x00), (markers[0].fill as ScenePaint.Solid).color)
        assertEquals(Rgba(0x00, 0xB0, 0x50), (markers[3].fill as ScenePaint.Solid).color)
    }

    @Test
    fun `stacked areas stack their bands and single areas fade`() {
        val series = listOf(
            "Alpha" to listOf(10.0, 20.0),
            "Beta" to listOf(30.0, 10.0),
        )

        val stacked = drawing(deck(areaChart(series, grouping = "stacked")))
        val bands = stacked.shapes.filter { it.closed && it.fill != null && it.points.size == 4 }
        assertEquals(2, bands.size)
        assertEquals(Rgba(0xC0, 0x00, 0x00), (bands[0].fill as ScenePaint.Solid).color)
        assertEquals(Rgba(0x00, 0xB0, 0x50), (bands[1].fill as ScenePaint.Solid).color)
        assertEquals(bands[0].points[0].x, bands[1].points[3].x, 1e-6)
        assertEquals(bands[0].points[0].y, bands[1].points[3].y, 1e-6)

        val overlapping = drawing(deck(areaChart(series, grouping = "standard")))
        val faded = overlapping.shapes.first { it.closed && it.fill != null && it.points.size == 4 }
        assertEquals(Rgba(0xC0, 0x00, 0x00, 160), (faded.fill as ScenePaint.Solid).color)
    }

    @Test
    fun `scatter points map both values and connect when asked`() {
        val drawing = drawing(
            deck(
                scatterChart(
                    listOf(Triple("Alpha", listOf(0.0, 10.0, 20.0), listOf(5.0, 15.0, 10.0))),
                ),
            ),
        )

        val markers = drawing.shapes.filter { it.points.size == 12 }
        assertEquals(3, markers.size)
        assertTrue("x grows left to right", markers[0].center().x < markers[1].center().x)
        assertTrue("the larger value plots higher", markers[1].center().y < markers[0].center().y)

        assertEquals(1, drawing.shapes.count { !it.closed && it.points.size == 3 })
    }

    @Test
    fun `pie points take a palette colour each and list their categories`() {
        val drawing = drawing(deck(pieChart(listOf(1.0, 2.0, 3.0), legend = true)))

        val wedges = drawing.shapes.filter { it.closed && it.fill != null && it.points.size > 4 }
        assertEquals(3, wedges.size)
        assertEquals(
            listOf(Rgba(0xC0, 0x00, 0x00), Rgba(0x00, 0xB0, 0x50), Rgba(0xA5, 0xA5, 0xA5)),
            wedges.map { (it.fill as ScenePaint.Solid).color },
        )
        assertTrue("a wider slice spans more geometry", wedges[2].points.size > wedges[0].points.size)

        val texts = texts(drawing)
        assertTrue("no categories in $texts", listOf("A", "B", "C").all { it in texts })
    }

    @Test
    fun `doughnut slices carry an inner ring and honour per-point fills`() {
        val pie = drawing(deck(pieChart(listOf(1.0, 1.0))))
        val doughnut = drawing(deck(pieChart(listOf(1.0, 1.0), doughnut = true, pointFills = mapOf(1 to "123456"))))

        val pieSize = pie.shapes.first { it.closed && it.points.size > 4 }.points.size
        val slices = doughnut.shapes.filter { it.closed && it.fill != null && it.points.size > 4 }
        assertEquals(2, slices.size)
        assertEquals(2 * pieSize, slices[0].points.size)
        assertEquals(Rgba(0x12, 0x34, 0x56), (slices[1].fill as ScenePaint.Solid).color)
    }

    @Test
    fun `data labels print the value beside each bar`() {
        val drawing = drawing(
            deck(
                columnChart(
                    dir = "col",
                    grouping = "clustered",
                    series = listOf("Alpha" to listOf(10.0, 20.0)),
                    labels = true,
                ),
            ),
        )

        val texts = texts(drawing)
        assertTrue("no data labels in $texts", listOf("10", "20").all { it in texts })
    }

    @Test
    fun `unsupported charts show a labelled placeholder and the alt text`() {
        val chart = """
            <c:chartSpace xmlns:c="$CHART_NS" xmlns:a="$A_NS" xmlns:r="$R_NS">
              <c:chart>
                <c:title><c:tx><c:rich><a:bodyPr/><a:p><a:r><a:t>Radar</a:t></a:r></a:p></c:rich></c:tx></c:title>
                <c:plotArea><c:radarChart/></c:plotArea>
              </c:chart>
            </c:chartSpace>
        """.trimIndent()

        val drawing = drawing(deck(chart, description = "Sales by quarter"))
        val frame = drawing.shapes.single { it.points.size == 4 && it.fill == null }
        assertEquals("dash", frame.line?.dash)
        val texts = texts(drawing)
        assertTrue("no chart title in $texts", "Radar" in texts)
        assertTrue("no placeholder in $texts", "Chart not displayed" in texts)
        assertTrue("no alt text in $texts", "Sales by quarter" in texts)
    }

    @Test
    fun `extended charts use their fallback picture`() {
        val content = """
            <mc:AlternateContent xmlns:mc="$MC_NS" xmlns:cx="$CX_NS" xmlns:c="$CHART_NS" xmlns:a="$A_NS" xmlns:r="$R_NS">
              <mc:Choice Requires="cx"><c:chart r:id="rId2"/></mc:Choice>
              <mc:Fallback>
                <p:pic xmlns:p="$P_NS">
                  <p:nvPicPr><p:cNvPr id="9" name="fallback"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>
                  <p:blipFill><a:blip r:embed="rId3"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>
                  <p:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="100" cy="100"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr>
                </p:pic>
              </mc:Fallback>
            </mc:AlternateContent>
        """.trimIndent()
        val result = deck("ignored", content = content).let { source ->
            val pkg = PptxPackage.read({ source.toBytes().inputStream() }, newParserFactory = OfficeFixtures::parser)
            val presentation = PresentationParser(OfficeFixtures::parser)
                .parse(source.parts.getValue("ppt/presentation.xml"))
            StyleResolver(OfficeFixtures::parser).resolveAll(pkg, presentation).slides.single().scene
        }
        val frame = result.nodes.filterIsInstance<SceneGraphicFrame>().single { it.name == "chart" }
        assertNotNull("no fallback picture", frame.fallbackPicture)
        assertEquals(null, frame.chart)
    }

    private fun Affine.isTranslation(): Boolean = a == 1.0 && b == 0.0 && c == 0.0 && d == 1.0

    private fun ChartShape.center(): io.github.pixelrick420.papyrus.viewer.pptx.ScenePoint =
        io.github.pixelrick420.papyrus.viewer.pptx.ScenePoint(
            points.map { it.x }.average(),
            points.map { it.y }.average(),
        )

    private fun rectangle(points: List<io.github.pixelrick420.papyrus.viewer.pptx.ScenePoint>): SceneRect {
        val xs = points.map { it.x }
        val ys = points.map { it.y }
        return SceneRect(xs.min(), ys.min(), xs.max() - xs.min(), ys.max() - ys.min())
    }

    private fun columnChart(
        dir: String,
        grouping: String,
        series: List<Pair<String, List<Double>>>,
        title: String? = null,
        legend: Boolean = false,
        labels: Boolean = false,
    ): String {
        val plot = series.mapIndexed { index, (name, values) -> seriesXml(index, name, values, labels = labels) }.joinToString("")
        val titleXml = title?.let {
            """<c:title><c:tx><c:rich><a:bodyPr/><a:p><a:r><a:t>$it</a:t></a:r></a:p></c:rich></c:tx></c:title>"""
        }.orEmpty()
        val legendXml = if (legend) """<c:legend><c:legendPos val="r"/></c:legend>""" else ""
        return """
            <c:chartSpace xmlns:c="$CHART_NS" xmlns:a="$A_NS" xmlns:r="$R_NS">
              <c:chart>
                $titleXml
                <c:plotArea>
                  <c:barChart>
                    <c:barDir val="$dir"/>
                    <c:grouping val="$grouping"/>
                    <c:gapWidth val="150"/>
                    $plot
                    <c:axId val="1"/>
                    <c:axId val="2"/>
                  </c:barChart>
                  ${axes(dir == "bar")}
                </c:plotArea>
                $legendXml
              </c:chart>
            </c:chartSpace>
        """.trimIndent()
    }

    private fun seriesXml(
        index: Int,
        name: String,
        values: List<Double>,
        marker: Boolean = false,
        labels: Boolean = false,
        extra: String = "",
    ): String {
        val points = values.mapIndexed { i, value -> """<c:pt idx="$i"><c:v>$value</c:v></c:pt>""" }.joinToString("")
        val categories = values.indices.map { i ->
            """<c:pt idx="$i"><c:v>${('A' + i)}</c:v></c:pt>"""
        }.joinToString("")
        val markerXml = if (marker) """<c:marker><c:symbol val="circle"/></c:marker>""" else ""
        val labelsXml = if (labels) """<c:dLbls><c:showVal val="1"/></c:dLbls>""" else ""
        return """
            <c:ser>
              <c:idx val="$index"/>
              <c:order val="$index"/>
              <c:tx><c:strRef><c:strCache><c:ptCount val="1"/><c:pt idx="0"><c:v>$name</c:v></c:pt></c:strCache></c:strRef></c:tx>
              $markerXml
              $labelsXml
              $extra
              <c:cat><c:strRef><c:strCache><c:ptCount val="${values.size}"/>$categories</c:strCache></c:strRef></c:cat>
              <c:val><c:numRef><c:numCache><c:formatCode>General</c:formatCode><c:ptCount val="${values.size}"/>$points</c:numCache></c:numRef></c:val>
            </c:ser>
        """.trimIndent()
    }

    private fun lineChart(
        series: List<Pair<String, List<Double>>>,
        markers: Boolean = true,
        labels: Boolean = false,
    ): String {
        val plot = series.mapIndexed { index, (name, values) ->
            seriesXml(index, name, values, marker = markers, labels = labels)
        }.joinToString("")
        return """
            <c:chartSpace xmlns:c="$CHART_NS" xmlns:a="$A_NS" xmlns:r="$R_NS">
              <c:chart>
                <c:plotArea>
                  <c:lineChart>
                    <c:grouping val="standard"/>
                    $plot
                    <c:axId val="1"/>
                    <c:axId val="2"/>
                  </c:lineChart>
                  ${axes(false)}
                </c:plotArea>
              </c:chart>
            </c:chartSpace>
        """.trimIndent()
    }

    private fun areaChart(
        series: List<Pair<String, List<Double>>>,
        grouping: String = "standard",
    ): String {
        val plot = series.mapIndexed { index, (name, values) -> seriesXml(index, name, values) }.joinToString("")
        return """
            <c:chartSpace xmlns:c="$CHART_NS" xmlns:a="$A_NS" xmlns:r="$R_NS">
              <c:chart>
                <c:plotArea>
                  <c:areaChart>
                    <c:grouping val="$grouping"/>
                    $plot
                    <c:axId val="1"/>
                    <c:axId val="2"/>
                  </c:areaChart>
                  ${axes(false)}
                </c:plotArea>
              </c:chart>
            </c:chartSpace>
        """.trimIndent()
    }

    private fun scatterChart(
        series: List<Triple<String, List<Double>, List<Double>>>,
        connect: Boolean = true,
    ): String {
        val plot = series.mapIndexed { index, (name, xs, ys) -> scatterSeriesXml(index, name, xs, ys) }.joinToString("")
        val style = if (connect) "lineMarker" else "marker"
        return """
            <c:chartSpace xmlns:c="$CHART_NS" xmlns:a="$A_NS" xmlns:r="$R_NS">
              <c:chart>
                <c:plotArea>
                  <c:scatterChart>
                    <c:scatterStyle val="$style"/>
                    $plot
                    <c:axId val="1"/>
                    <c:axId val="2"/>
                  </c:scatterChart>
                  <c:valAx>
                    <c:axId val="1"/>
                    <c:scaling><c:orientation val="minMax"/></c:scaling>
                    <c:axPos val="b"/>
                    <c:numFmt formatCode="General" sourceLinked="1"/>
                    <c:crossAx val="2"/>
                  </c:valAx>
                  <c:valAx>
                    <c:axId val="2"/>
                    <c:scaling><c:orientation val="minMax"/></c:scaling>
                    <c:axPos val="l"/>
                    <c:majorGridlines/>
                    <c:numFmt formatCode="General" sourceLinked="1"/>
                    <c:crossAx val="1"/>
                  </c:valAx>
                </c:plotArea>
              </c:chart>
            </c:chartSpace>
        """.trimIndent()
    }

    private fun scatterSeriesXml(index: Int, name: String, xs: List<Double>, ys: List<Double>): String {
        fun cache(tag: String, values: List<Double>): String {
            val points = values.mapIndexed { i, value -> """<c:pt idx="$i"><c:v>$value</c:v></c:pt>""" }.joinToString("")
            return """<c:$tag><c:numRef><c:numCache><c:formatCode>General</c:formatCode>""" +
                """<c:ptCount val="${values.size}"/>$points</c:numCache></c:numRef></c:$tag>"""
        }
        return """
            <c:ser>
              <c:idx val="$index"/>
              <c:order val="$index"/>
              <c:tx><c:strRef><c:strCache><c:ptCount val="1"/><c:pt idx="0"><c:v>$name</c:v></c:pt></c:strCache></c:strRef></c:tx>
              ${cache("xVal", xs)}
              ${cache("yVal", ys)}
            </c:ser>
        """.trimIndent()
    }

    private fun pieChart(
        values: List<Double>,
        doughnut: Boolean = false,
        legend: Boolean = false,
        pointFills: Map<Int, String> = emptyMap(),
        labels: Boolean = false,
    ): String {
        val points = pointFills.entries.joinToString("") { (index, hex) ->
            """<c:dPt><c:idx val="$index"/><c:spPr><a:solidFill><a:srgbClr val="$hex"/></a:solidFill></c:spPr></c:dPt>"""
        }
        val series = seriesXml(0, "Share", values, labels = labels, extra = points)
        val legendXml = if (legend) """<c:legend><c:legendPos val="r"/></c:legend>""" else ""
        val container = if (doughnut) "doughnutChart" else "pieChart"
        val hole = if (doughnut) """<c:holeSize val="75"/>""" else ""
        return """
            <c:chartSpace xmlns:c="$CHART_NS" xmlns:a="$A_NS" xmlns:r="$R_NS">
              <c:chart>
                <c:plotArea>
                  <c:$container>
                    <c:varyColors val="1"/>
                    $hole
                    $series
                  </c:$container>
                </c:plotArea>
                $legendXml
              </c:chart>
            </c:chartSpace>
        """.trimIndent()
    }

    private fun axes(horizontal: Boolean): String {
        val categoryPos = if (horizontal) "l" else "b"
        val valuePos = if (horizontal) "b" else "l"
        return """
            <c:catAx>
              <c:axId val="1"/>
              <c:scaling><c:orientation val="minMax"/></c:scaling>
              <c:axPos val="$categoryPos"/>
              <c:crossAx val="2"/>
            </c:catAx>
            <c:valAx>
              <c:axId val="2"/>
              <c:scaling><c:orientation val="minMax"/></c:scaling>
              <c:axPos val="$valuePos"/>
              <c:majorGridlines/>
              <c:numFmt formatCode="General" sourceLinked="1"/>
              <c:crossAx val="1"/>
            </c:valAx>
        """.trimIndent()
    }

    private companion object {
        const val CHART_NS = "http://schemas.openxmlformats.org/drawingml/2006/chart"
        const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"
        const val R_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        const val CHART_URI = "http://schemas.openxmlformats.org/drawingml/2006/chart"
        const val CHART_RELATIONSHIP = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart"
        const val CHART_X = 100_000L
        const val CHART_Y = 50_000L
        const val CHART_WIDTH = 600_000L
        const val CHART_HEIGHT = 400_000L
        const val MC_NS = "http://schemas.openxmlformats.org/markup-compatibility/2006"
        const val CX_NS = "http://schemas.microsoft.com/office/drawing/2014/chart"
        const val P_NS = "http://schemas.openxmlformats.org/presentationml/2006/main"
    }
}
