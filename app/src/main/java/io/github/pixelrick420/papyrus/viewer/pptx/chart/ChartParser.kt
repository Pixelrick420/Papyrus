package io.github.pixelrick420.papyrus.viewer.pptx.chart

import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlNamespace
import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import io.github.pixelrick420.papyrus.viewer.pptx.XNode

internal object ChartParser {

    private const val CHART_NS = "http://schemas.openxmlformats.org/drawingml/2006/chart"

    fun parse(chartSpace: XNode, limits: PptxLimits): PptxChart? {
        val chart = chartSpace.child(CHART_NS, "chart") ?: return null
        val plotAreaNode = chart.child(CHART_NS, "plotArea") ?: return null
        val containers = parseContainers(plotAreaNode, limits) ?: return null
        val used = containers.filter { it.supported && it.series.isNotEmpty() }
        val axes = if (used.isNotEmpty()) parseAxes(plotAreaNode) else emptyList()
        val groups = axes.associate { it.id to it.group }
        val series = used.flatMap { container ->
            container.series.map { ser ->
                val group = if (container.axisIds.any { groups[it] == AxisGroup.SECONDARY }) {
                    AxisGroup.SECONDARY
                } else {
                    AxisGroup.PRIMARY
                }
                ser.copy(axisGroup = group)
            }
        }
        val supported = series.isNotEmpty()
        val primary = used.firstOrNull()
        val type = when {
            used.any { it.type == ChartType.STOCK } -> ChartType.STOCK
            used.size > 1 -> ChartType.COMBO
            else -> primary?.type ?: ChartType.OTHER
        }
        val plotArea = ChartPlotArea(
            manualLayout = plotAreaNode.child(CHART_NS, "layout")?.let(::parseManualLayout),
            series = series,
            axes = axes,
            dataLabels = primary?.dataLabels ?: parseDataLabels(plotAreaNode),
            shapeProperties = plotAreaNode.child(CHART_NS, "spPr"),
        )
        return PptxChart(
            title = chart.child(CHART_NS, "title")?.let(::parseTitle),
            plotArea = plotArea,
            legend = chart.child(CHART_NS, "legend")?.let(::parseLegend),
            shapeProperties = chartSpace.child(CHART_NS, "spPr"),
            textProperties = chartSpace.child(CHART_NS, "txPr"),
            style = chartSpace.child(CHART_NS, "style")?.attribute("val")?.toIntOrNull(),
            type = type,
            varyColors = primary?.varyColors ?: false,
            gapWidth = primary?.gapWidth,
            overlap = primary?.overlap,
            holeSize = primary?.holeSize,
            firstSliceAngle = primary?.firstSliceAngle,
            scatterStyle = primary?.scatterStyle,
            radarStyle = primary?.radarStyle ?: RadarStyle.MARKER,
            upDownBars = parseUpDownBars(plotAreaNode),
            supported = supported,
        )
    }

    private class Container(
        val type: ChartType,
        val supported: Boolean,
        val series: List<ChartSeries>,
        val axisIds: List<Int> = emptyList(),
        val varyColors: Boolean = false,
        val gapWidth: Int? = null,
        val overlap: Int? = null,
        val holeSize: Int? = null,
        val firstSliceAngle: Double? = null,
        val scatterStyle: String? = null,
        val radarStyle: RadarStyle = RadarStyle.MARKER,
        val dataLabels: ChartDataLabels? = null,
    )

    private fun parseContainers(plotArea: XNode, limits: PptxLimits): List<Container>? {
        val containers = ArrayList<Container>()
        var seriesCount = 0
        var pointCount = 0
        for (child in plotArea.children) {
            val container = parseContainer(child) ?: continue
            for (ser in container.series) {
                seriesCount++
                if (seriesCount > limits.maxChartSeries) return null
                pointCount += maxOf(
                    ser.categories?.points?.size ?: 0,
                    ser.values?.points?.size ?: 0,
                    ser.xValues?.points?.size ?: 0,
                    ser.yValues?.points?.size ?: 0,
                    ser.bubbleSizes?.points?.size ?: 0,
                    ser.openValues?.points?.size ?: 0,
                    ser.highValues?.points?.size ?: 0,
                    ser.lowValues?.points?.size ?: 0,
                    ser.closeValues?.points?.size ?: 0,
                )
                if (pointCount > limits.maxChartPoints) return null
            }
            containers += container
        }
        return containers
    }

    private fun parseContainer(node: XNode): Container? {
        if (node.name.namespace != CHART_NS) return null
        val varyColors = node.child(CHART_NS, "varyColors")?.let { on(it.attribute("val")) }
        return when (node.name.localName) {
            "barChart" -> {
                val direction = if (node.child(CHART_NS, "barDir")?.attribute("val") == "bar") {
                    BarDirection.BAR
                } else {
                    BarDirection.COLUMN
                }
                val grouping = groupingOf(node)
                val type = if (direction == BarDirection.BAR) ChartType.BAR else ChartType.COLUMN
                Container(
                    type = type,
                    supported = true,
                    series = node.childrenOf(CHART_NS, "ser").map { ser ->
                        parseSeriesNode(ser, type, grouping = grouping, direction = direction)
                    },
                    varyColors = varyColors == true,
                    axisIds = axisIds(node),
                    gapWidth = node.child(CHART_NS, "gapWidth")?.attribute("val")?.toIntOrNull(),
                    overlap = node.child(CHART_NS, "overlap")?.attribute("val")?.toIntOrNull(),
                    dataLabels = node.child(CHART_NS, "dLbls")?.let(::parseDataLabels),
                )
            }
            "lineChart" -> Container(
                ChartType.LINE,
                supported = true,
                series = node.childrenOf(CHART_NS, "ser").map { ser -> parseLineSeries(ser, groupingOf(node)) },
                axisIds = axisIds(node),
                varyColors = varyColors == true,
                dataLabels = node.child(CHART_NS, "dLbls")?.let(::parseDataLabels),
            )
            "pieChart" -> Container(
                ChartType.PIE,
                supported = true,
                series = node.childrenOf(CHART_NS, "ser").map { parseSeriesNode(it, ChartType.PIE) },
                varyColors = varyColors ?: true,
                firstSliceAngle = node.child(CHART_NS, "firstSliceAng")?.attribute("val")?.toDoubleOrNull(),
                dataLabels = node.child(CHART_NS, "dLbls")?.let(::parseDataLabels),
            )
            "doughnutChart" -> Container(
                ChartType.DOUGHNUT,
                supported = true,
                series = node.childrenOf(CHART_NS, "ser").map { parseSeriesNode(it, ChartType.DOUGHNUT) },
                varyColors = varyColors ?: true,
                holeSize = node.child(CHART_NS, "holeSize")?.attribute("val")?.toIntOrNull(),
                firstSliceAngle = node.child(CHART_NS, "firstSliceAng")?.attribute("val")?.toDoubleOrNull(),
                dataLabels = node.child(CHART_NS, "dLbls")?.let(::parseDataLabels),
            )
            "areaChart" -> Container(
                ChartType.AREA,
                supported = true,
                series = node.childrenOf(CHART_NS, "ser").map { ser ->
                    parseSeriesNode(ser, ChartType.AREA, grouping = groupingOf(node))
                },
                axisIds = axisIds(node),
                varyColors = varyColors == true,
                dataLabels = node.child(CHART_NS, "dLbls")?.let(::parseDataLabels),
            )
            "scatterChart" -> Container(
                ChartType.SCATTER,
                supported = true,
                series = node.childrenOf(CHART_NS, "ser").map(::parseScatterSeries),
                axisIds = axisIds(node),
                varyColors = varyColors == true,
                scatterStyle = node.child(CHART_NS, "scatterStyle")?.attribute("val"),
                dataLabels = node.child(CHART_NS, "dLbls")?.let(::parseDataLabels),
            )
            "radarChart" -> Container(
                ChartType.RADAR,
                supported = true,
                series = node.childrenOf(CHART_NS, "ser").map { parseSeriesNode(it, ChartType.RADAR) },
                axisIds = axisIds(node),
                varyColors = varyColors == true,
                radarStyle = when (node.child(CHART_NS, "radarStyle")?.attribute("val")) {
                    "filled" -> RadarStyle.FILLED
                    "none" -> RadarStyle.NONE
                    else -> RadarStyle.MARKER
                },
                dataLabels = node.child(CHART_NS, "dLbls")?.let(::parseDataLabels),
            )
            "bubbleChart" -> Container(
                ChartType.BUBBLE,
                supported = true,
                series = node.childrenOf(CHART_NS, "ser").map(::parseBubbleSeries),
                axisIds = axisIds(node),
                varyColors = varyColors == true,
                dataLabels = node.child(CHART_NS, "dLbls")?.let(::parseDataLabels),
            )
            "stockChart" -> Container(
                ChartType.STOCK,
                supported = true,
                series = node.childrenOf(CHART_NS, "ser").map(::parseStockSeries),
                axisIds = axisIds(node),
                varyColors = varyColors == true,
                dataLabels = node.child(CHART_NS, "dLbls")?.let(::parseDataLabels),
            )
            "bar3DChart", "line3DChart", "pie3DChart", "ofPieChart",
            "surfaceChart", "surface3DChart",
            -> Container(ChartType.OTHER, supported = false, series = emptyList())
            else -> null
        }
    }

    private fun axisIds(node: XNode): List<Int> =
        node.childrenOf(CHART_NS, "axId").mapNotNull { it.attribute("val")?.toIntOrNull() }

    private fun groupingOf(node: XNode): BarGrouping =
        when (node.child(CHART_NS, "grouping")?.attribute("val")) {
            "stacked" -> BarGrouping.STACKED
            "percentStacked" -> BarGrouping.PERCENT_STACKED
            else -> BarGrouping.CLUSTERED
        }

    private fun parseLineSeries(ser: XNode, grouping: BarGrouping): ChartSeries {
        val marker = ser.child(CHART_NS, "marker")?.child(CHART_NS, "symbol")?.attribute("val") != "none"
        return parseSeriesNode(ser, ChartType.LINE, grouping = grouping, marker = marker)
    }

    private fun parseScatterSeries(ser: XNode): ChartSeries {
        val xValues = ser.child(CHART_NS, "xVal")?.let(::parseCacheRef)
        val yValues = ser.child(CHART_NS, "yVal")?.let(::parseCacheRef)
        val idx = ser.child(CHART_NS, "idx")?.attribute("val")?.toIntOrNull() ?: 0
        val order = ser.child(CHART_NS, "order")?.attribute("val")?.toIntOrNull() ?: idx
        return ChartSeries(
            index = idx,
            order = order,
            name = seriesName(ser),
            type = ChartType.SCATTER,
            categories = xValues,
            values = yValues,
            xValues = xValues,
            yValues = yValues,
            shapeProperties = ser.child(CHART_NS, "spPr"),
            textProperties = ser.child(CHART_NS, "txPr"),
            dataLabels = ser.child(CHART_NS, "dLbls")?.let(::parseDataLabels),
            trendlines = ser.childrenOf(CHART_NS, "trendline").mapNotNull(::parseTrendline),
            errorBars = ser.childrenOf(CHART_NS, "errBars").mapNotNull(::parseErrorBars),
            points = parsePoints(ser),
        )
    }

    private fun parseBubbleSeries(ser: XNode): ChartSeries {
        val base = parseScatterSeries(ser)
        return base.copy(
            type = ChartType.BUBBLE,
            bubbleSizes = ser.child(CHART_NS, "bubbleSize")?.let(::parseCacheRef),
        )
    }

    private fun parseStockSeries(ser: XNode): ChartSeries {
        val base = parseSeriesNode(ser, ChartType.STOCK)
        return base.copy(
            openValues = ser.child(CHART_NS, "open")?.let(::parseCacheRef),
            highValues = ser.child(CHART_NS, "high")?.let(::parseCacheRef),
            lowValues = ser.child(CHART_NS, "low")?.let(::parseCacheRef),
            closeValues = ser.child(CHART_NS, "close")?.let(::parseCacheRef),
        )
    }

    private fun parseTrendline(node: XNode): ChartTrendline? {
        val type = when (node.child(CHART_NS, "trendlineType")?.attribute("val")) {
            "log" -> TrendlineType.LOGARITHMIC
            "exp" -> TrendlineType.EXPONENTIAL
            "power" -> TrendlineType.POWER
            "poly" -> TrendlineType.POLYNOMIAL
            "movingAvg" -> TrendlineType.MOVING_AVERAGE
            else -> TrendlineType.LINEAR
        }
        return ChartTrendline(
            type = type,
            order = node.child(CHART_NS, "order")?.attribute("val")?.toIntOrNull() ?: 2,
            period = node.child(CHART_NS, "period")?.attribute("val")?.toIntOrNull() ?: 2,
            displayEquation = on(node.child(CHART_NS, "dispEq")?.attribute("val")),
            displayRSqr = on(node.child(CHART_NS, "dispRSqr")?.attribute("val")),
            name = node.child(CHART_NS, "name")?.text?.trim()?.ifEmpty { null },
            shapeProperties = node.child(CHART_NS, "spPr"),
        )
    }

    private fun parseErrorBars(node: XNode): ChartErrorBars? {
        val direction = if (node.child(CHART_NS, "errDir")?.attribute("val") == "x") {
            ErrorBarDirection.X
        } else {
            ErrorBarDirection.Y
        }
        val (type, value) = when (node.child(CHART_NS, "errValType")?.attribute("val")) {
            "percentage" -> ErrorBarType.PERCENT to node.child(CHART_NS, "val")?.attribute("val")?.toDoubleOrNull()
            "stdDev" -> ErrorBarType.STANDARD_DEVIATION to node.child(CHART_NS, "val")?.attribute("val")?.toDoubleOrNull()
            "stdErr" -> ErrorBarType.STANDARD_ERROR to null
            "cust" -> ErrorBarType.CUSTOM to null
            else -> ErrorBarType.FIXED_VALUE to node.child(CHART_NS, "val")?.attribute("val")?.toDoubleOrNull()
        }
        return ChartErrorBars(
            direction = direction,
            type = type,
            value = value,
            plus = node.child(CHART_NS, "plus")?.let(::parseCacheRef),
            minus = node.child(CHART_NS, "minus")?.let(::parseCacheRef),
            showPositive = node.child(CHART_NS, "errBarType")?.attribute("val") != "minus",
            showNegative = node.child(CHART_NS, "errBarType")?.attribute("val") != "plus",
            noEndCaps = on(node.child(CHART_NS, "noEndCap")?.attribute("val")),
            shapeProperties = node.child(CHART_NS, "spPr"),
        )
    }

    private fun parseUpDownBars(node: XNode): ChartUpDownBars? {
        val bars = node.child(CHART_NS, "upDownBars")
            ?: node.children.firstNotNullOfOrNull { it.child(CHART_NS, "upDownBars") }
            ?: return null
        return ChartUpDownBars(
            gapWidth = bars.child(CHART_NS, "gapWidth")?.attribute("val")?.toIntOrNull() ?: 150,
            upBars = bars.child(CHART_NS, "upBars")?.child(CHART_NS, "spPr"),
            downBars = bars.child(CHART_NS, "downBars")?.child(CHART_NS, "spPr"),
            hide = on(bars.child(CHART_NS, "hide")?.attribute("val")),
        )
    }

    private fun parseSeriesNode(
        ser: XNode,
        type: ChartType,
        grouping: BarGrouping? = null,
        direction: BarDirection? = null,
        marker: Boolean = false,
    ): ChartSeries {
        val idx = ser.child(CHART_NS, "idx")?.attribute("val")?.toIntOrNull() ?: 0
        val order = ser.child(CHART_NS, "order")?.attribute("val")?.toIntOrNull() ?: idx
        return ChartSeries(
            index = idx,
            order = order,
            name = seriesName(ser),
            type = type,
            grouping = grouping,
            direction = direction,
            marker = marker,
            categories = ser.child(CHART_NS, "cat")?.let(::parseCacheRef),
            values = ser.child(CHART_NS, "val")?.let(::parseCacheRef),
            shapeProperties = ser.child(CHART_NS, "spPr"),
            textProperties = ser.child(CHART_NS, "txPr"),
            dataLabels = ser.child(CHART_NS, "dLbls")?.let(::parseDataLabels),
            trendlines = ser.childrenOf(CHART_NS, "trendline").mapNotNull(::parseTrendline),
            errorBars = ser.childrenOf(CHART_NS, "errBars").mapNotNull(::parseErrorBars),
            points = parsePoints(ser),
        )
    }

    private fun parsePoints(ser: XNode): List<ChartDataPoint> =
        ser.childrenOf(CHART_NS, "dPt").mapNotNull { point ->
            val index = point.child(CHART_NS, "idx")?.attribute("val")?.toIntOrNull() ?: return@mapNotNull null
            ChartDataPoint(
                index = index,
                shapeProperties = point.child(CHART_NS, "spPr"),
                explosion = point.child(CHART_NS, "explosion")?.attribute("val")?.toDoubleOrNull(),
            )
        }

    private fun seriesName(ser: XNode): String? {
        val tx = ser.child(CHART_NS, "tx") ?: return null
        tx.child(CHART_NS, "strRef")?.let { return firstCachedValue(it) }
        tx.child(CHART_NS, "v")?.let { return it.text.trim().ifEmpty { null } }
        return null
    }

    private fun parseCacheRef(node: XNode): ChartCache? {
        val ref = node.child(CHART_NS, "numRef")
            ?: node.child(CHART_NS, "strRef")
            ?: node.child(CHART_NS, "numLit")
            ?: node.child(CHART_NS, "strLit")
            ?: node.child(CHART_NS, "multiLvlStrRef")
        return ref?.let(::parseCache)
    }

    private fun parseCache(ref: XNode): ChartCache? {
        val cache = ref.child(CHART_NS, "numCache")
            ?: ref.child(CHART_NS, "strCache")
            ?: ref.child(CHART_NS, "multiLvlStrCache")
            ?: ref.takeIf { it.name.localName == "numLit" || it.name.localName == "strLit" }
            ?: return null
        val formatCode = cache.child(CHART_NS, "formatCode")?.text?.trim()
        val points = ArrayList<ChartCacheValue>()
        for (pt in cache.childrenOf(CHART_NS, "pt")) {
            val idx = pt.attribute("idx")?.toIntOrNull() ?: continue
            val v = pt.child(CHART_NS, "v")?.text?.trim().orEmpty()
            points += ChartCacheValue(idx, v, v.toDoubleOrNull())
        }
        if (points.isEmpty() && cache.child(CHART_NS, "ptCount") == null) return null
        points.sortBy { it.index }
        return ChartCache(formatCode, points)
    }

    private fun firstCachedValue(ref: XNode): String? =
        parseCache(ref)?.points?.firstOrNull()?.text?.takeIf { it.isNotEmpty() }

    private fun parseAxes(plotArea: XNode): List<ChartAxis> {
        val axes = ArrayList<ChartAxis>()
        for (child in plotArea.children) {
            if (child.name.namespace != CHART_NS) continue
            val kind = when (child.name.localName) {
                "catAx" -> AxisKind.CATEGORY
                "valAx" -> AxisKind.VALUE
                "dateAx" -> AxisKind.DATE
                "serAx" -> AxisKind.SERIES
                else -> continue
            }
            axes += parseAxis(child, kind)
        }
        return axes
    }

    private fun parseAxis(node: XNode, kind: AxisKind): ChartAxis {
        val scalingNode = node.child(CHART_NS, "scaling")
        val scaling = if (scalingNode != null) {
            ChartScaling(
                min = scalingNode.child(CHART_NS, "min")?.attribute("val")?.toDoubleOrNull(),
                max = scalingNode.child(CHART_NS, "max")?.attribute("val")?.toDoubleOrNull(),
                majorUnit = scalingNode.child(CHART_NS, "majorUnit")?.attribute("val")?.toDoubleOrNull(),
                minorUnit = scalingNode.child(CHART_NS, "minorUnit")?.attribute("val")?.toDoubleOrNull(),
                logBase = scalingNode.child(CHART_NS, "logBase")?.attribute("val")?.toDoubleOrNull(),
                orientation = if (scalingNode.child(CHART_NS, "orientation")?.attribute("val") == "maxMin") {
                    AxisOrientation.MAX_MIN
                } else {
                    AxisOrientation.MIN_MAX
                },
            )
        } else {
            ChartScaling()
        }
        val position = node.child(CHART_NS, "axPos")?.attribute("val")
        return ChartAxis(
            id = node.child(CHART_NS, "axId")?.attribute("val")?.toIntOrNull() ?: 0,
            kind = kind,
            position = position,
            group = if (position == "r" || position == "t") AxisGroup.SECONDARY else AxisGroup.PRIMARY,
            scaling = scaling,
            majorGridlines = node.child(CHART_NS, "majorGridlines"),
            minorGridlines = node.child(CHART_NS, "minorGridlines"),
            majorTickMark = parseTickMark(node.child(CHART_NS, "majorTickMark")?.attribute("val"), TickMark.CROSS),
            minorTickMark = parseTickMark(node.child(CHART_NS, "minorTickMark")?.attribute("val"), TickMark.NONE),
            tickLabelPosition = parseTickLabelPosition(node.child(CHART_NS, "tickLblPos")?.attribute("val")),
            crossingAxisId = node.child(CHART_NS, "crossAx")?.attribute("val")?.toIntOrNull(),
            crosses = node.child(CHART_NS, "crosses")?.attribute("val"),
            crossesAt = node.child(CHART_NS, "crossesAt")?.attribute("val")?.toDoubleOrNull(),
            numberFormat = node.child(CHART_NS, "numFmt")?.let { ChartNumberFormats.parse(it.attribute("formatCode")) },
            title = node.child(CHART_NS, "title")?.let(::parseTitle),
            shapeProperties = node.child(CHART_NS, "spPr"),
            textProperties = node.child(CHART_NS, "txPr"),
            delete = node.child(CHART_NS, "delete")?.attribute("val") == "1",
        )
    }

    private fun parseTickMark(value: String?, default: TickMark): TickMark = when (value) {
        "none" -> TickMark.NONE
        "in" -> TickMark.IN
        "out" -> TickMark.OUT
        "cross" -> TickMark.CROSS
        else -> default
    }

    private fun parseTickLabelPosition(value: String?): TickLabelPosition = when (value) {
        "none" -> TickLabelPosition.NONE
        "low" -> TickLabelPosition.LOW
        "high" -> TickLabelPosition.HIGH
        "nextTo" -> TickLabelPosition.NEXT_TO
        else -> TickLabelPosition.NEXT_TO
    }

    private fun parseTitle(node: XNode): ChartTitle {
        val tx = node.child(CHART_NS, "tx")
        val rich = tx?.child(CHART_NS, "rich")
        val strRef = tx?.child(CHART_NS, "strRef")
        val text = when {
            rich != null -> richTextOf(rich)
            strRef != null -> firstCachedValue(strRef) ?: ""
            else -> ""
        }
        return ChartTitle(
            text = text,
            richText = rich,
            shapeProperties = node.child(CHART_NS, "spPr"),
            textProperties = node.child(CHART_NS, "txPr"),
            manualLayout = node.child(CHART_NS, "layout")?.let(::parseManualLayout),
            overlay = node.child(CHART_NS, "overlay")?.attribute("val") == "1",
        )
    }

    private fun richTextOf(node: XNode): String = buildString {
        for (p in node.childrenOf(OoxmlNamespace.A, "p")) {
            if (isNotEmpty()) append('\n')
            for (r in p.children) {
                if (r.name.matches(OoxmlNamespace.A, "r") || r.name.matches(OoxmlNamespace.A, "fld")) {
                    r.child(OoxmlNamespace.A, "t")?.text?.let { append(it) }
                }
            }
        }
    }.trim()

    private fun parseLegend(node: XNode): ChartLegend {
        val pos = when (node.child(CHART_NS, "legendPos")?.attribute("val")) {
            "t" -> LegendPosition.TOP
            "b" -> LegendPosition.BOTTOM
            "l" -> LegendPosition.LEFT
            "tr" -> LegendPosition.TOP_RIGHT
            "r" -> LegendPosition.RIGHT
            else -> LegendPosition.RIGHT
        }
        return ChartLegend(
            position = pos,
            overlay = node.child(CHART_NS, "overlay")?.attribute("val") == "1",
            manualLayout = node.child(CHART_NS, "layout")?.let(::parseManualLayout),
            shapeProperties = node.child(CHART_NS, "spPr"),
            textProperties = node.child(CHART_NS, "txPr"),
        )
    }

    private fun parseDataLabels(node: XNode): ChartDataLabels? {
        val dLbls = node.child(CHART_NS, "dLbls") ?: return null
        return ChartDataLabels(
            showLegendKey = on(dLbls.child(CHART_NS, "showLegendKey")?.attribute("val")),
            showValue = on(dLbls.child(CHART_NS, "showVal")?.attribute("val")),
            showCategoryName = on(dLbls.child(CHART_NS, "showCatName")?.attribute("val")),
            showSeriesName = on(dLbls.child(CHART_NS, "showSerName")?.attribute("val")),
            showPercent = on(dLbls.child(CHART_NS, "showPercent")?.attribute("val")),
            showBubbleSize = on(dLbls.child(CHART_NS, "showBubbleSize")?.attribute("val")),
            position = parseDataLabelPosition(dLbls.child(CHART_NS, "dLblPos")?.attribute("val")),
            numberFormat = dLbls.child(CHART_NS, "numFmt")?.let { ChartNumberFormats.parse(it.attribute("formatCode")) },
            shapeProperties = dLbls.child(CHART_NS, "spPr"),
            textProperties = dLbls.child(CHART_NS, "txPr"),
        )
    }

    private fun parseDataLabelPosition(value: String?): DataLabelPosition? = when (value) {
        "bestFit" -> DataLabelPosition.BEST_FIT
        "ctr" -> DataLabelPosition.CENTER
        "inEnd" -> DataLabelPosition.INSIDE_END
        "inBase" -> DataLabelPosition.INSIDE_BASE
        "outEnd" -> DataLabelPosition.OUTSIDE_END
        "l" -> DataLabelPosition.LEFT
        "r" -> DataLabelPosition.RIGHT
        "t" -> DataLabelPosition.TOP
        "b" -> DataLabelPosition.BOTTOM
        else -> null
    }

    private fun parseManualLayout(layout: XNode): ChartManualLayout? {
        val ml = layout.child(CHART_NS, "manualLayout") ?: return null
        fun value(name: String): Double? = ml.child(CHART_NS, name)?.attribute("val")?.toDoubleOrNull()
        fun mode(name: String): String? = ml.child(CHART_NS, name)?.attribute("val")
        return ChartManualLayout(
            x = value("x"),
            y = value("y"),
            w = value("w"),
            h = value("h"),
            xMode = mode("xMode"),
            yMode = mode("yMode"),
            wMode = mode("wMode"),
            hMode = mode("hMode"),
            layoutTarget = ml.child(CHART_NS, "layoutTarget")?.attribute("val"),
        )
    }

    private fun on(value: String?): Boolean = value == "1" || value.equals("true", ignoreCase = true)
}
