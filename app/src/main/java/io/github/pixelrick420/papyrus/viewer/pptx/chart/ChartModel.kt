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

internal enum class AxisKind { CATEGORY, VALUE, DATE, SERIES }

internal enum class AxisOrientation { MIN_MAX, MAX_MIN }

internal enum class TickMark { NONE, IN, OUT, CROSS }

internal enum class TickLabelPosition { NONE, LOW, HIGH, NEXT_TO }

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

internal data class ChartNumberFormat(
    val sourceCode: String,
    val formatCode: String,
)

internal data class ChartScaling(
    val min: Double? = null,
    val max: Double? = null,
    val majorUnit: Double? = null,
    val minorUnit: Double? = null,
    val logBase: Double? = null,
    val orientation: AxisOrientation = AxisOrientation.MIN_MAX,
)

internal data class ChartAxis(
    val id: Int,
    val kind: AxisKind,
    val position: String?,
    val scaling: ChartScaling = ChartScaling(),
    val majorGridlines: XNode? = null,
    val minorGridlines: XNode? = null,
    val majorTickMark: TickMark = TickMark.CROSS,
    val minorTickMark: TickMark = TickMark.NONE,
    val tickLabelPosition: TickLabelPosition = TickLabelPosition.NEXT_TO,
    val crossingAxisId: Int? = null,
    val crosses: String? = null,
    val crossesAt: Double? = null,
    val numberFormat: ChartNumberFormat? = null,
    val delete: Boolean = false,
)

internal data class ChartPlotArea(
    val series: List<ChartSeries> = emptyList(),
    val axes: List<ChartAxis> = emptyList(),
)

internal data class PptxChart(
    val plotArea: ChartPlotArea,
)
