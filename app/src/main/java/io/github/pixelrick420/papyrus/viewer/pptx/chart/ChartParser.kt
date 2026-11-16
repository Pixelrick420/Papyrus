package io.github.pixelrick420.papyrus.viewer.pptx.chart

import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import io.github.pixelrick420.papyrus.viewer.pptx.XNode

internal object ChartParser {

    private const val CHART_NS = "http://schemas.openxmlformats.org/drawingml/2006/chart"

    fun parse(chartSpace: XNode, limits: PptxLimits): PptxChart? {
        val chart = chartSpace.child(CHART_NS, "chart") ?: return null
        val plotAreaNode = chart.child(CHART_NS, "plotArea") ?: return null
        val series = parseSeries(plotAreaNode, limits) ?: return null
        val axes = parseAxes(plotAreaNode)
        return PptxChart(plotArea = ChartPlotArea(series = series, axes = axes))
    }

    private fun parseSeries(plotArea: XNode, limits: PptxLimits): List<ChartSeries>? {
        val series = ArrayList<ChartSeries>()
        var seriesCount = 0
        var pointCount = 0
        for (child in plotArea.children) {
            val parsed = parseSeriesContainer(child) ?: continue
            for (ser in parsed) {
                seriesCount++
                if (seriesCount > limits.maxChartSeries) return null
                val count = maxOf(
                    ser.categories?.points?.size ?: 0,
                    ser.values?.points?.size ?: 0,
                    ser.xValues?.points?.size ?: 0,
                    ser.yValues?.points?.size ?: 0,
                )
                pointCount += count
                if (pointCount > limits.maxChartPoints) return null
                series += ser
            }
        }
        return series
    }

    private fun parseSeriesContainer(node: XNode): List<ChartSeries>? {
        if (node.name.namespace != CHART_NS) return null
        return when (node.name.localName) {
            "barChart", "bar3DChart" -> parseBarLike(node)
            "lineChart", "line3DChart", "stockChart" -> parseLineLike(node)
            "pieChart", "pie3DChart", "ofPieChart" -> parseTypedSeries(node, ChartType.PIE)
            "doughnutChart" -> parseTypedSeries(node, ChartType.DOUGHNUT)
            "areaChart", "area3DChart" -> parseTypedSeries(node, ChartType.AREA)
            "scatterChart", "bubbleChart" -> parseScatterSeries(node)
            "radarChart", "surfaceChart", "surface3DChart" -> parseTypedSeries(node, ChartType.OTHER)
            else -> null
        }
    }

    private fun parseBarLike(node: XNode): List<ChartSeries> {
        val direction = if (node.child(CHART_NS, "barDir")?.attribute("val") == "bar") {
            BarDirection.BAR
        } else {
            BarDirection.COLUMN
        }
        val grouping = when (node.child(CHART_NS, "grouping")?.attribute("val")) {
            "stacked" -> BarGrouping.STACKED
            "percentStacked" -> BarGrouping.PERCENT_STACKED
            else -> BarGrouping.CLUSTERED
        }
        val type = if (direction == BarDirection.BAR) ChartType.BAR else ChartType.COLUMN
        return node.childrenOf(CHART_NS, "ser").map { ser ->
            parseSeriesNode(ser, type, grouping = grouping, direction = direction)
        }
    }

    private fun parseLineLike(node: XNode): List<ChartSeries> =
        node.childrenOf(CHART_NS, "ser").map { ser ->
            val marker = ser.child(CHART_NS, "marker")?.child(CHART_NS, "symbol")?.attribute("val") != "none"
            parseSeriesNode(ser, ChartType.LINE, marker = marker)
        }

    private fun parseTypedSeries(node: XNode, type: ChartType): List<ChartSeries> =
        node.childrenOf(CHART_NS, "ser").map { ser -> parseSeriesNode(ser, type) }

    private fun parseScatterSeries(node: XNode): List<ChartSeries> =
        node.childrenOf(CHART_NS, "ser").map { ser ->
            val idx = ser.child(CHART_NS, "idx")?.attribute("val")?.toIntOrNull() ?: 0
            val order = ser.child(CHART_NS, "order")?.attribute("val")?.toIntOrNull() ?: idx
            val xValues = ser.child(CHART_NS, "xVal")?.let(::parseCacheRef)
            val yValues = ser.child(CHART_NS, "yVal")?.let(::parseCacheRef)
            ChartSeries(
                index = idx,
                order = order,
                name = seriesName(ser),
                type = ChartType.SCATTER,
                categories = xValues,
                values = yValues,
                xValues = xValues,
                yValues = yValues,
                shapeProperties = ser.child(CHART_NS, "spPr"),
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
            ?: if (ref.name.localName == "numLit" || ref.name.localName == "strLit") ref else null
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
        return ChartAxis(
            id = node.child(CHART_NS, "axId")?.attribute("val")?.toIntOrNull() ?: 0,
            kind = kind,
            position = node.child(CHART_NS, "axPos")?.attribute("val"),
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
}
