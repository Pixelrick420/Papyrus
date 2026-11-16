package io.github.pixelrick420.papyrus.viewer.pptx.chart

import io.github.pixelrick420.papyrus.viewer.pptx.XNode

internal enum class ChartType {
    BAR,
    COLUMN,
    LINE,
    PIE,
    DOUGHNUT,
    AREA,
    SCATTER,
    OTHER,
}

internal enum class BarGrouping { CLUSTERED, STACKED, PERCENT_STACKED }

internal enum class BarDirection { BAR, COLUMN }

internal data class ChartCacheValue(val index: Int, val text: String, val number: Double?)

internal data class ChartCache(val formatCode: String?, val points: List<ChartCacheValue>)

internal data class ChartSeries(
    val index: Int,
    val order: Int,
    val name: String?,
    val type: ChartType,
    val grouping: BarGrouping? = null,
    val direction: BarDirection? = null,
    val marker: Boolean = false,
    val categories: ChartCache?,
    val values: ChartCache?,
    val xValues: ChartCache? = null,
    val yValues: ChartCache? = null,
    val shapeProperties: XNode? = null,
)

internal data class ChartPlotArea(
    val series: List<ChartSeries> = emptyList(),
)

internal data class PptxChart(
    val plotArea: ChartPlotArea,
)
