package io.github.pixelrick420.papyrus.viewer.pptx.chart

import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
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

internal enum class LegendPosition { RIGHT, TOP, BOTTOM, LEFT, TOP_RIGHT, NONE }

internal enum class DataLabelPosition { BEST_FIT, CENTER, INSIDE_END, INSIDE_BASE, OUTSIDE_END, LEFT, RIGHT, TOP, BOTTOM }

internal data class ChartCacheValue(val index: Int, val text: String, val number: Double?)

internal data class ChartCache(val formatCode: String?, val points: List<ChartCacheValue>)

internal data class ChartDataPoint(
    val index: Int,
    val shapeProperties: XNode? = null,
    val explosion: Double? = null,
)

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
    val textProperties: XNode? = null,
    val dataLabels: ChartDataLabels? = null,
    val points: List<ChartDataPoint> = emptyList(),
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

internal data class ChartManualLayout(
    val x: Double? = null,
    val y: Double? = null,
    val w: Double? = null,
    val h: Double? = null,
    val xMode: String? = null,
    val yMode: String? = null,
    val wMode: String? = null,
    val hMode: String? = null,
    val layoutTarget: String? = null,
)

internal data class ChartTitle(
    val text: String,
    val richText: XNode? = null,
    val shapeProperties: XNode? = null,
    val textProperties: XNode? = null,
    val manualLayout: ChartManualLayout? = null,
    val overlay: Boolean = false,
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
    val title: ChartTitle? = null,
    val shapeProperties: XNode? = null,
    val textProperties: XNode? = null,
    val delete: Boolean = false,
)

internal data class ChartDataLabels(
    val showLegendKey: Boolean = false,
    val showValue: Boolean = false,
    val showCategoryName: Boolean = false,
    val showSeriesName: Boolean = false,
    val showPercent: Boolean = false,
    val showBubbleSize: Boolean = false,
    val position: DataLabelPosition? = null,
    val numberFormat: ChartNumberFormat? = null,
    val shapeProperties: XNode? = null,
    val textProperties: XNode? = null,
)

internal data class ChartLegend(
    val position: LegendPosition = LegendPosition.RIGHT,
    val overlay: Boolean = false,
    val manualLayout: ChartManualLayout? = null,
    val shapeProperties: XNode? = null,
    val textProperties: XNode? = null,
)

internal data class ChartPlotArea(
    val manualLayout: ChartManualLayout? = null,
    val series: List<ChartSeries> = emptyList(),
    val axes: List<ChartAxis> = emptyList(),
    val dataLabels: ChartDataLabels? = null,
    val shapeProperties: XNode? = null,
)

internal data class ChartLayoutResult(
    val plotArea: SceneRect,
    val legend: SceneRect?,
    val title: SceneRect?,
)

internal data class PptxChart(
    val title: ChartTitle?,
    val plotArea: ChartPlotArea,
    val legend: ChartLegend?,
    val shapeProperties: XNode? = null,
    val textProperties: XNode? = null,
    val layout: ChartLayoutResult? = null,
    val type: ChartType = ChartType.OTHER,
    val varyColors: Boolean = false,
    val gapWidth: Int? = null,
    val overlap: Int? = null,
    val holeSize: Int? = null,
    val firstSliceAngle: Double? = null,
    val scatterStyle: String? = null,
    val supported: Boolean = false,
)
