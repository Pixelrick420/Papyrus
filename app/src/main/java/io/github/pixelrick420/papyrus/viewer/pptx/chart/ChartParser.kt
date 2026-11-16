package io.github.pixelrick420.papyrus.viewer.pptx.chart

import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import io.github.pixelrick420.papyrus.viewer.pptx.XNode

internal object ChartParser {

    private const val CHART_NS = "http://schemas.openxmlformats.org/drawingml/2006/chart"

    fun parse(chartSpace: XNode, limits: PptxLimits): PptxChart? {
        val chart = chartSpace.child(CHART_NS, "chart") ?: return null
        val plotAreaNode = chart.child(CHART_NS, "plotArea") ?: return null
        val series = parseSeries(plotAreaNode, limits) ?: return null
        return PptxChart(plotArea = ChartPlotArea(series = series))
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
}
