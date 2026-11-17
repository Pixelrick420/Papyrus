package io.github.pixelrick420.papyrus.viewer.pptx.chart

import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.SceneLine
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePoint
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

internal object ChartBuilder {

    private const val EMU_PER_POINT = 12_700.0
    private const val TITLE_SIZE_PT = 14.0
    private const val LABEL_SIZE_PT = 9.0
    private const val LEGEND_SIZE_PT = 9.0
    private const val TICK_SIZE_PT = 9.0
    private const val LINE_EMU = 9_525.0

    fun build(chart: PptxChart, bounds: SceneRect, style: ChartStyle, description: String?): ChartDrawing {
        if (bounds.width <= 0.0 || bounds.height <= 0.0) return ChartDrawing()
        val layout = ChartLayout.layout(chart, bounds)
        val shapes = ArrayList<ChartShape>()
        val texts = ArrayList<ChartText>()
        style.fillOf(chart.shapeProperties)?.let { shapes += filled(bounds, it) }
        if (!chart.supported || chart.type == ChartType.OTHER) {
            placeholder(chart, layout, bounds, style, description, shapes, texts)
            return ChartDrawing(shapes, texts)
        }
        style.fillOf(chart.plotArea.shapeProperties)?.let { shapes += filled(layout.plotArea, it) }
        title(chart, layout, style, texts)
        when (chart.type) {
            ChartType.BAR, ChartType.COLUMN -> bars(chart, layout.plotArea, style, shapes, texts)
            ChartType.LINE -> lines(chart, layout.plotArea, style, shapes, texts)
            ChartType.AREA -> areas(chart, layout.plotArea, style, shapes, texts)
            ChartType.SCATTER -> scatter(chart, layout.plotArea, style, shapes, texts)
            ChartType.PIE, ChartType.DOUGHNUT -> pie(chart, layout.plotArea, style, shapes, texts)
            ChartType.OTHER -> Unit
        }
        legend(chart, layout.legend, style, shapes, texts)
        return ChartDrawing(shapes, texts)
    }

    private fun placeholder(
        chart: PptxChart,
        layout: ChartLayoutResult,
        bounds: SceneRect,
        style: ChartStyle,
        description: String?,
        shapes: MutableList<ChartShape>,
        texts: MutableList<ChartText>,
    ) {
        shapes += ChartShape(
            points = rectanglePoints(bounds),
            fill = null,
            line = SceneLine(3 * EMU_PER_POINT, ScenePaint.Solid(style.axis), "dash"),
            closed = true,
        )
        title(chart, layout, style, texts)
        val top = layout.title?.bottom ?: bounds.y
        val middle = SceneRect(bounds.x, top, bounds.width, (bounds.bottom - top).coerceAtLeast(0.0))
        val message = SceneRect(
            middle.x + middle.width * 0.1,
            middle.y + middle.height * 0.4,
            middle.width * 0.8,
            middle.height * 0.2,
        )
        texts += ChartText(ChartTextFactory.body("Chart not displayed", LABEL_SIZE_PT * 1.2, style.text), message)
        if (!description.isNullOrBlank()) {
            val alt = SceneRect(middle.x + middle.width * 0.1, message.bottom, middle.width * 0.8, middle.height * 0.15)
            texts += ChartText(ChartTextFactory.body(description, LABEL_SIZE_PT, style.text), alt)
        }
    }

    private fun title(chart: PptxChart, layout: ChartLayoutResult, style: ChartStyle, texts: MutableList<ChartText>) {
        val title = chart.title ?: return
        if (title.text.isBlank()) return
        val rect = layout.title ?: return
        texts += ChartText(ChartTextFactory.body(title.text, TITLE_SIZE_PT, style.text, bold = true), rect)
    }

    private fun bars(
        chart: PptxChart,
        rect: SceneRect,
        style: ChartStyle,
        shapes: MutableList<ChartShape>,
        texts: MutableList<ChartText>,
    ) {
        val series = chart.plotArea.series
        val count = categoryCount(series)
        if (count <= 0) return
        val horizontal = chart.type == ChartType.BAR
        val grouping = series.firstOrNull()?.grouping ?: BarGrouping.CLUSTERED
        val percent = grouping == BarGrouping.PERCENT_STACKED
        val stacked = grouping != BarGrouping.CLUSTERED
        val values = series.map { cacheArray(it.values, count) }
        val categoryAxis = categoryAxis(chart)
        val valueAxis = valueAxis(chart)
        val range = if (percent) ValueRange(0.0, 1.0, 0.2) else valueRange(totals(values, stacked, count), valueAxis)
        val labels = categoryLabels(series, count)
        axes(chart, rect, range, horizontal, style, shapes)
        categoryTicks(categoryAxis, labels, rect, count, horizontal, style, texts)
        valueTicks(valueAxis, rect, range, horizontal, style, texts)

        val slot = catLength(rect, horizontal) / count
        val gap = ((chart.gapWidth ?: 150).coerceIn(0, 500)) / 100.0
        val groupWidth = slot / (1.0 + gap)
        val clustered = grouping == BarGrouping.CLUSTERED
        val barWidth = if (clustered) groupWidth / series.size.coerceAtLeast(1) else groupWidth
        val overlap = ((chart.overlap ?: if (clustered) 0 else 100).coerceIn(-100, 100)) / 100.0
        val positive = DoubleArray(count)
        val negative = DoubleArray(count)
        val percentTotals = if (percent) categoryTotals(values, count) else null
        for ((s, ser) in series.withIndex()) {
            val labelsOfSeries = ser.dataLabels ?: chart.plotArea.dataLabels
            for (c in 0 until count) {
                val raw = values[s][c] ?: continue
                val total = percentTotals?.get(c) ?: 0.0
                val value = if (percent && total > 0.0) raw / total else raw
                val start: Double
                val end: Double
                if (stacked) {
                    if (value >= 0.0) {
                        start = positive[c]
                        end = start + value
                        positive[c] = end
                    } else {
                        start = negative[c]
                        end = start + value
                        negative[c] = end
                    }
                } else {
                    start = 0.0
                    end = value
                }
                val offset = c * slot + (slot - groupWidth) / 2.0 + s * barWidth * (1.0 - overlap)
                val bar = if (horizontal) {
                    val x1 = valueX(rect, range, start, valueAxis)
                    val x2 = valueX(rect, range, end, valueAxis)
                    SceneRect(min(x1, x2), rect.y + offset, abs(x2 - x1), barWidth)
                } else {
                    val y1 = valueY(rect, range, start, valueAxis)
                    val y2 = valueY(rect, range, end, valueAxis)
                    SceneRect(rect.x + offset, min(y1, y2), barWidth, abs(y2 - y1))
                }
                if (bar.width <= 0.0 || bar.height <= 0.0) continue
                shapes += ChartShape(
                    rectanglePoints(bar),
                    seriesPaint(ser, c, chart.varyColors, style),
                    seriesLine(ser, c, style),
                )
                dataLabel(labelsOfSeries, ser, raw, value, labels.getOrNull(c), horizontal, bar, slot, style, texts)
            }
        }
    }

    private fun lines(
        chart: PptxChart,
        rect: SceneRect,
        style: ChartStyle,
        shapes: MutableList<ChartShape>,
        texts: MutableList<ChartText>,
    ) {
        val series = chart.plotArea.series
        val count = categoryCount(series)
        if (count <= 0) return
        val categoryAxis = categoryAxis(chart)
        val valueAxis = valueAxis(chart)
        val grouping = series.firstOrNull()?.grouping ?: BarGrouping.CLUSTERED
        val grouped = grouping != BarGrouping.CLUSTERED
        val percent = grouping == BarGrouping.PERCENT_STACKED
        val values = series.map { cacheArray(it.values, count) }
        val range = if (percent) ValueRange(0.0, 1.0, 0.2) else valueRange(totals(values, grouped, count), valueAxis)
        val labels = categoryLabels(series, count)
        axes(chart, rect, range, horizontal = false, style, shapes)
        categoryTicks(categoryAxis, labels, rect, count, false, style, texts)
        valueTicks(valueAxis, rect, range, false, style, texts)
        val positive = DoubleArray(count)
        val negative = DoubleArray(count)
        val percentTotals = if (percent) categoryTotals(values, count) else null
        for ((s, ser) in series.withIndex()) {
            val dLbls = ser.dataLabels ?: chart.plotArea.dataLabels
            val points = ArrayList<ScenePoint>()
            val pointValues = ArrayList<Double>()
            for (c in 0 until count) {
                val raw = values[s][c] ?: continue
                val total = percentTotals?.get(c) ?: 0.0
                val value = if (percent && total > 0.0) raw / total else raw
                val placed = if (grouped) {
                    if (value >= 0.0) {
                        val position = positive[c] + value
                        positive[c] = position
                        position
                    } else {
                        val position = negative[c] + value
                        negative[c] = position
                        position
                    }
                } else {
                    value
                }
                points += ScenePoint(categoryX(rect, c, count, categoryAxis), valueY(rect, range, placed, valueAxis))
                pointValues += raw
            }
            if (points.isEmpty()) continue
            val line = seriesLine(ser, -1, style) ?: defaultLine(seriesColor(ser, -1, false, style))
            if (points.size > 1) shapes += ChartShape(points, fill = null, line = line, closed = false)
            if (ser.marker) {
                for (point in points) shapes += marker(point, seriesPaint(ser, -1, false, style))
            }
            for ((index, point) in points.withIndex()) {
                val label = labels.getOrNull(index)
                val raw = pointValues.getOrNull(index) ?: continue
                dataLabel(dLbls, ser, raw, raw, label, false, SceneRect(point.x, point.y, 0.0, 0.0), rect.width / count, style, texts)
            }
        }
    }

    private fun areas(
        chart: PptxChart,
        rect: SceneRect,
        style: ChartStyle,
        shapes: MutableList<ChartShape>,
        texts: MutableList<ChartText>,
    ) {
        val series = chart.plotArea.series
        val count = categoryCount(series)
        if (count <= 0) return
        val categoryAxis = categoryAxis(chart)
        val valueAxis = valueAxis(chart)
        val grouping = series.firstOrNull()?.grouping ?: BarGrouping.CLUSTERED
        val grouped = grouping != BarGrouping.CLUSTERED
        val percent = grouping == BarGrouping.PERCENT_STACKED
        val values = series.map { cacheArray(it.values, count) }
        val range = if (percent) ValueRange(0.0, 1.0, 0.2) else valueRange(totals(values, grouped, count), valueAxis)
        val labels = categoryLabels(series, count)
        axes(chart, rect, range, horizontal = false, style, shapes)
        categoryTicks(categoryAxis, labels, rect, count, false, style, texts)
        valueTicks(valueAxis, rect, range, false, style, texts)
        val positive = DoubleArray(count)
        val negative = DoubleArray(count)
        val percentTotals = if (percent) categoryTotals(values, count) else null
        for ((s, ser) in series.withIndex()) {
            val dLbls = ser.dataLabels ?: chart.plotArea.dataLabels
            val top = ArrayList<ScenePoint>()
            val bottom = ArrayList<ScenePoint>()
            val topValues = ArrayList<Pair<Double, String?>>()
            for (c in 0 until count) {
                val raw = values[s][c] ?: continue
                val total = percentTotals?.get(c) ?: 0.0
                val value = if (percent && total > 0.0) raw / total else raw
                val base: Double
                val end: Double
                if (grouped) {
                    if (value >= 0.0) {
                        base = positive[c]
                        end = base + value
                        positive[c] = end
                    } else {
                        base = negative[c]
                        end = base + value
                        negative[c] = end
                    }
                } else {
                    base = 0.0
                    end = value
                }
                val x = categoryX(rect, c, count, categoryAxis)
                top += ScenePoint(x, valueY(rect, range, end, valueAxis))
                bottom += ScenePoint(x, valueY(rect, range, base, valueAxis))
                topValues += raw to labels.getOrNull(c)
            }
            if (top.isEmpty()) continue
            val color = seriesColor(ser, -1, false, style)
            val fill = explicitFill(ser, -1, style)
                ?: ScenePaint.Solid(if (grouped) color else Rgba(color.red, color.green, color.blue, 160))
            val line = seriesLine(ser, -1, style) ?: defaultLine(color)
            shapes += ChartShape(top + bottom.reversed(), fill = fill, line = line, closed = true)
            if (top.size > 1) shapes += ChartShape(top, fill = null, line = line, closed = false)
            if (dLbls?.hasContent() == true) {
                for ((index, point) in top.withIndex()) {
                    val (raw, label) = topValues.getOrNull(index) ?: continue
                    dataLabel(dLbls, ser, raw, raw, label, false, SceneRect(point.x, point.y, 0.0, 0.0), rect.width / count, style, texts)
                }
            }
        }
    }

    private fun scatter(
        chart: PptxChart,
        rect: SceneRect,
        style: ChartStyle,
        shapes: MutableList<ChartShape>,
        texts: MutableList<ChartText>,
    ) {
        val series = chart.plotArea.series
        val valueAxes = chart.plotArea.axes.filter { it.kind == AxisKind.VALUE }
        val xAxis = valueAxes.firstOrNull { it.position == "b" } ?: valueAxes.getOrNull(0)
        val yAxis = valueAxes.firstOrNull { it.position == "l" } ?: valueAxes.getOrNull(1)
        val xValues = series.map { cacheArray(it.xValues ?: it.categories, Int.MAX_VALUE) }
        val yValues = series.map { cacheArray(it.yValues ?: it.values, Int.MAX_VALUE) }
        val rangeX = valueRange(xValues.flatMap { it.filterNotNull() }, xAxis)
        val rangeY = valueRange(yValues.flatMap { it.filterNotNull() }, yAxis)
        axes(chart, rect, rangeY, horizontal = false, style, shapes)
        valueTicks(yAxis, rect, rangeY, horizontal = false, style, texts)
        scatterTicks(xAxis, rect, rangeX, style, texts)
        val connect = chart.scatterStyle?.lowercase()?.contains("line") == true
        for ((s, ser) in series.withIndex()) {
            val dLbls = ser.dataLabels ?: chart.plotArea.dataLabels
            val points = ArrayList<ScenePoint>()
            val size = minOf(xValues[s].size, yValues[s].size)
            for (i in 0 until size) {
                val x = xValues[s][i] ?: continue
                val y = yValues[s][i] ?: continue
                val point = ScenePoint(valueX(rect, rangeX, x, xAxis), valueY(rect, rangeY, y, yAxis))
                points += point
                dataLabel(dLbls, ser, y, y, null, false, SceneRect(point.x, point.y, 0.0, 0.0), rect.width / size.coerceAtLeast(1), style, texts)
            }
            if (connect && points.size > 1) {
                shapes += ChartShape(points, fill = null, line = defaultLine(seriesColor(ser, -1, false, style)), closed = false)
            }
            for (point in points) shapes += marker(point, seriesPaint(ser, -1, false, style))
        }
    }

    private fun pie(
        chart: PptxChart,
        rect: SceneRect,
        style: ChartStyle,
        shapes: MutableList<ChartShape>,
        texts: MutableList<ChartText>,
    ) {
        val series = chart.plotArea.series
        if (series.isEmpty()) return
        val radius = min(rect.width, rect.height) / 2.0 * 0.9
        if (radius <= 0.0) return
        val center = ScenePoint(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0)
        val doughnut = chart.type == ChartType.DOUGHNUT
        val holeRatio = if (doughnut) ((chart.holeSize ?: 50).coerceIn(10, 90)) / 100.0 else 0.0
        val hole = radius * holeRatio
        val rings = if (doughnut) series.size else 1
        val thickness = if (doughnut) (radius - hole) / rings.coerceAtLeast(1) else radius - hole
        for ((s, ser) in series.withIndex()) {
            val outer = radius - s * thickness
            val inner = (outer - thickness).coerceAtLeast(hole)
            if (outer <= inner) continue
            val values = cacheArray(ser.values, Int.MAX_VALUE).filterNotNull()
            if (values.isEmpty()) continue
            val total = values.sumOf { abs(it) }
            if (total <= 0.0) continue
            val categories = categoryLabels(listOf(ser), values.size)
            var angle = chart.firstSliceAngle ?: 0.0
            val dLbls = ser.dataLabels ?: chart.plotArea.dataLabels
            for ((index, value) in values.withIndex()) {
                val sweep = value / total * 360.0
                val point = ser.points.firstOrNull { it.index == index }
                val explosion = (point?.explosion ?: 0.0) / 100.0 * radius
                val middle = Math.toRadians(angle + sweep / 2.0)
                val shifted = ScenePoint(center.x + sin(middle) * explosion, center.y - cos(middle) * explosion)
                val fill = seriesPaint(ser, index, true, style)
                val line = seriesLine(ser, index, style) ?: SceneLine(EMU_PER_POINT, ScenePaint.Solid(style.text), null)
                shapes += ChartShape(wedge(shifted, outer, inner, angle, angle + sweep), fill, line)
                if (dLbls?.hasContent() == true) {
                    dataLabel(
                        dLbls,
                        ser,
                        value,
                        value / total,
                        categories.getOrNull(index),
                        false,
                        SceneRect(
                            shifted.x + sin(middle) * (inner + (outer - inner) * 0.6),
                            shifted.y - cos(middle) * (inner + (outer - inner) * 0.6),
                            0.0,
                            0.0,
                        ),
                        rect.width * 0.3,
                        style,
                        texts,
                    )
                }
                angle += sweep
            }
        }
    }

    private fun axes(
        chart: PptxChart,
        rect: SceneRect,
        range: ValueRange,
        horizontal: Boolean,
        style: ChartStyle,
        shapes: MutableList<ChartShape>,
    ) {
        val valueAxis = valueAxis(chart)
        if (valueAxis?.majorGridlines != null) {
            val paint = ScenePaint.Solid(style.grid)
            for (tick in range.ticks()) {
                if (horizontal) {
                    val x = valueX(rect, range, tick, valueAxis)
                    shapes += line(x, rect.y, x, rect.bottom, paint)
                } else {
                    val y = valueY(rect, range, tick, valueAxis)
                    shapes += line(rect.x, y, rect.right, y, paint)
                }
            }
        }
        if (valueAxis?.delete != true) {
            val paint = ScenePaint.Solid(style.axis)
            if (horizontal) shapes += line(rect.x, rect.bottom, rect.right, rect.bottom, paint)
            else shapes += line(rect.x, rect.y, rect.x, rect.bottom, paint)
        }
        val category = categoryAxis(chart)
        if (category?.delete != true) {
            val paint = ScenePaint.Solid(style.axis)
            if (horizontal) shapes += line(rect.x, rect.y, rect.x, rect.bottom, paint)
            else shapes += line(rect.x, rect.bottom, rect.right, rect.bottom, paint)
        }
    }

    private fun categoryTicks(
        axis: ChartAxis?,
        labels: List<String>,
        rect: SceneRect,
        count: Int,
        horizontal: Boolean,
        style: ChartStyle,
        texts: MutableList<ChartText>,
    ) {
        if (axis?.tickLabelPosition == TickLabelPosition.NONE || axis?.delete == true || labels.isEmpty()) return
        val slot = catLength(rect, horizontal) / count
        for (c in 0 until count) {
            val label = labels.getOrNull(c) ?: break
            if (label.isEmpty()) continue
            val offset = c * slot + slot / 2.0
            val frame = if (horizontal) {
                SceneRect(
                    rect.x - 60 * EMU_PER_POINT,
                    rect.y + offset - slot / 2.0,
                    56 * EMU_PER_POINT,
                    min(slot, 18 * EMU_PER_POINT),
                )
            } else {
                SceneRect(rect.x + offset - slot / 2.0, rect.bottom + 2 * EMU_PER_POINT, slot, 14 * EMU_PER_POINT)
            }
            texts += ChartText(ChartTextFactory.body(label, TICK_SIZE_PT, style.text), frame)
        }
    }

    private fun valueTicks(
        axis: ChartAxis?,
        rect: SceneRect,
        range: ValueRange,
        horizontal: Boolean,
        style: ChartStyle,
        texts: MutableList<ChartText>,
    ) {
        if (axis?.tickLabelPosition == TickLabelPosition.NONE || axis?.delete == true) return
        for (tick in range.ticks()) {
            val label = ChartNumberFormats.format(tick, axis?.numberFormat)
            if (label.isEmpty()) continue
            val frame = if (horizontal) {
                val x = valueX(rect, range, tick, axis)
                SceneRect(x - 30 * EMU_PER_POINT, rect.bottom + 2 * EMU_PER_POINT, 60 * EMU_PER_POINT, 14 * EMU_PER_POINT)
            } else {
                val y = valueY(rect, range, tick, axis)
                SceneRect(rect.x - 62 * EMU_PER_POINT, y - 7 * EMU_PER_POINT, 58 * EMU_PER_POINT, 14 * EMU_PER_POINT)
            }
            texts += ChartText(
                ChartTextFactory.body(label, TICK_SIZE_PT, style.text, align = if (horizontal) "ctr" else "r"),
                frame,
            )
        }
    }

    private fun scatterTicks(
        axis: ChartAxis?,
        rect: SceneRect,
        range: ValueRange,
        style: ChartStyle,
        texts: MutableList<ChartText>,
    ) {
        if (axis?.tickLabelPosition == TickLabelPosition.NONE || axis?.delete == true) return
        for (tick in range.ticks()) {
            val label = ChartNumberFormats.format(tick, axis?.numberFormat)
            if (label.isEmpty()) continue
            val x = valueX(rect, range, tick, axis)
            val frame = SceneRect(x - 30 * EMU_PER_POINT, rect.bottom + 2 * EMU_PER_POINT, 60 * EMU_PER_POINT, 14 * EMU_PER_POINT)
            texts += ChartText(ChartTextFactory.body(label, TICK_SIZE_PT, style.text), frame)
        }
    }

    private fun legend(
        chart: PptxChart,
        rect: SceneRect?,
        style: ChartStyle,
        shapes: MutableList<ChartShape>,
        texts: MutableList<ChartText>,
    ) {
        rect ?: return
        val entries = legendEntries(chart, style)
        if (entries.isEmpty()) return
        val vertical = chart.legend?.position != LegendPosition.TOP && chart.legend?.position != LegendPosition.BOTTOM
        if (vertical) {
            val height = min(rect.height / entries.size, 18 * EMU_PER_POINT)
            for ((index, entry) in entries.withIndex()) {
                val y = rect.y + index * height
                if (y + height > rect.bottom + 1.0) break
                val swatch = SceneRect(rect.x, y + (height - 8 * EMU_PER_POINT) / 2.0, 8 * EMU_PER_POINT, 8 * EMU_PER_POINT)
                shapes += ChartShape(rectanglePoints(swatch), entry.fill, entry.line)
                val text = SceneRect(swatch.right + 4 * EMU_PER_POINT, y, (rect.right - swatch.right - 4 * EMU_PER_POINT).coerceAtLeast(0.0), height)
                texts += ChartText(ChartTextFactory.body(entry.label, LEGEND_SIZE_PT, style.text, align = "l"), text)
            }
        } else {
            val width = rect.width / entries.size
            for ((index, entry) in entries.withIndex()) {
                val x = rect.x + index * width
                val swatch = SceneRect(x, rect.y + (rect.height - 8 * EMU_PER_POINT) / 2.0, 8 * EMU_PER_POINT, 8 * EMU_PER_POINT)
                shapes += ChartShape(rectanglePoints(swatch), entry.fill, entry.line)
                val text = SceneRect(swatch.right + 4 * EMU_PER_POINT, rect.y, (width - 12 * EMU_PER_POINT).coerceAtLeast(0.0), rect.height)
                texts += ChartText(ChartTextFactory.body(entry.label, LEGEND_SIZE_PT, style.text, align = "l"), text)
            }
        }
    }

    private class LegendEntry(val label: String, val fill: ScenePaint?, val line: SceneLine?)

    private fun legendEntries(chart: PptxChart, style: ChartStyle): List<LegendEntry> =
        when (chart.type) {
            ChartType.PIE, ChartType.DOUGHNUT -> {
                val series = chart.plotArea.series.firstOrNull() ?: return emptyList()
                categoryLabels(listOf(series), categoryCount(listOf(series))).mapIndexed { index, label ->
                    LegendEntry(label, seriesPaint(series, index, true, style), null)
                }
            }
            else -> chart.plotArea.series.map { series ->
                val line = if (chart.type == ChartType.LINE || chart.type == ChartType.SCATTER) {
                    seriesLine(series, -1, style) ?: defaultLine(seriesColor(series, -1, false, style))
                } else {
                    null
                }
                val fill = if (line != null) null else seriesPaint(series, -1, false, style)
                LegendEntry(series.name ?: "Series ${series.order + 1}", fill, line)
            }
        }

    private fun dataLabel(
        labels: ChartDataLabels?,
        series: ChartSeries,
        raw: Double,
        value: Double,
        category: String?,
        horizontal: Boolean,
        anchor: SceneRect,
        slot: Double,
        style: ChartStyle,
        texts: MutableList<ChartText>,
    ) {
        if (labels?.hasContent() != true) return
        val text = labelText(labels, series.name, category, raw, value) ?: return
        val position = labels.position
        val frame = when {
            horizontal -> {
                val width = 60 * EMU_PER_POINT
                val left = if (value >= 0.0) anchor.right + 3 * EMU_PER_POINT else anchor.x - width - 3 * EMU_PER_POINT
                SceneRect(left, anchor.y, width, max(anchor.height, 14 * EMU_PER_POINT))
            }
            position == DataLabelPosition.CENTER -> SceneRect(anchor.x - slot / 2.0, anchor.y - 7 * EMU_PER_POINT, max(slot, 24 * EMU_PER_POINT), 14 * EMU_PER_POINT)
            position == DataLabelPosition.INSIDE_END || position == DataLabelPosition.INSIDE_BASE -> SceneRect(anchor.x - slot / 2.0, anchor.y + 1 * EMU_PER_POINT, max(slot, 24 * EMU_PER_POINT), 14 * EMU_PER_POINT)
            position == DataLabelPosition.BOTTOM -> SceneRect(anchor.x - slot / 2.0, anchor.y + 2 * EMU_PER_POINT, max(slot, 24 * EMU_PER_POINT), 14 * EMU_PER_POINT)
            position == DataLabelPosition.LEFT || position == DataLabelPosition.RIGHT -> {
                val width = 60 * EMU_PER_POINT
                val left = if (position == DataLabelPosition.RIGHT) anchor.right + 2 * EMU_PER_POINT else anchor.x - width - 2 * EMU_PER_POINT
                SceneRect(left, anchor.y - 7 * EMU_PER_POINT, width, 14 * EMU_PER_POINT)
            }
            else -> {
                val width = max(slot, 24 * EMU_PER_POINT)
                SceneRect(anchor.x - width / 2.0, anchor.y - 14 * EMU_PER_POINT, width, 14 * EMU_PER_POINT)
            }
        }
        texts += ChartText(ChartTextFactory.body(text, LABEL_SIZE_PT, style.text), frame)
    }

    private fun labelText(
        labels: ChartDataLabels,
        seriesName: String?,
        category: String?,
        raw: Double,
        value: Double,
    ): String? {
        val parts = ArrayList<String>()
        if (labels.showSeriesName && !seriesName.isNullOrBlank()) parts += seriesName
        if (labels.showCategoryName && !category.isNullOrBlank()) parts += category
        if (labels.showValue) parts += ChartNumberFormats.format(raw, labels.numberFormat)
        if (labels.showPercent) {
            val format = labels.numberFormat ?: ChartNumberFormat("0%", "0%")
            parts += ChartNumberFormats.format(value, format)
        }
        if (parts.isEmpty()) return null
        return parts.joinToString("\n")
    }

    private fun ChartDataLabels.hasContent(): Boolean =
        showValue || showCategoryName || showSeriesName || showPercent

    private fun explicitFill(series: ChartSeries, pointIndex: Int, style: ChartStyle): ScenePaint? {
        val point = if (pointIndex >= 0) series.points.firstOrNull { it.index == pointIndex } else null
        return style.fillOf(point?.shapeProperties) ?: style.fillOf(series.shapeProperties)
    }

    private fun seriesPaint(
        series: ChartSeries,
        pointIndex: Int,
        varyByPoint: Boolean,
        style: ChartStyle,
    ): ScenePaint = explicitFill(series, pointIndex, style)
        ?: ScenePaint.Solid(seriesColor(series, pointIndex, varyByPoint, style))

    private fun seriesLine(series: ChartSeries, pointIndex: Int, style: ChartStyle): SceneLine? {
        val point = if (pointIndex >= 0) series.points.firstOrNull { it.index == pointIndex } else null
        return style.lineOf(point?.shapeProperties) ?: style.lineOf(series.shapeProperties)
    }

    private fun seriesColor(series: ChartSeries, pointIndex: Int, varyByPoint: Boolean, style: ChartStyle): Rgba {
        if (style.palette.isEmpty()) return Rgba.BLACK
        val slot = if (varyByPoint && pointIndex >= 0) pointIndex else series.order
        return style.palette[Math.floorMod(slot, style.palette.size)]
    }

    private fun defaultLine(color: Rgba): SceneLine = SceneLine(2 * EMU_PER_POINT, ScenePaint.Solid(color), null)

    private fun marker(center: ScenePoint, fill: ScenePaint?): ChartShape {
        val radius = 4 * EMU_PER_POINT
        val points = ArrayList<ScenePoint>(12)
        for (i in 0 until 12) {
            val angle = Math.toRadians(i * 30.0)
            points += ScenePoint(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
        }
        return ChartShape(points, fill ?: ScenePaint.Solid(Rgba.BLACK), SceneLine(EMU_PER_POINT, ScenePaint.Solid(Rgba.BLACK), null))
    }

    private fun wedge(center: ScenePoint, outer: Double, inner: Double, start: Double, end: Double): List<ScenePoint> {
        val sweep = end - start
        val steps = max(2, ceil(abs(sweep) / 4.0).toInt())
        val points = ArrayList<ScenePoint>(steps * 2 + 2)
        for (i in 0..steps) {
            val angle = Math.toRadians(start + sweep * i / steps)
            points += ScenePoint(center.x + sin(angle) * outer, center.y - cos(angle) * outer)
        }
        if (inner > 0.0) {
            for (i in steps downTo 0) {
                val angle = Math.toRadians(start + sweep * i / steps)
                points += ScenePoint(center.x + sin(angle) * inner, center.y - cos(angle) * inner)
            }
        }
        return points
    }

    private fun categoryCount(series: List<ChartSeries>): Int =
        series.maxOfOrNull { maxOf(it.categories?.points?.size ?: 0, it.values?.points?.size ?: 0) } ?: 0

    private fun categoryLabels(series: List<ChartSeries>, count: Int): List<String> {
        val cache = series.firstNotNullOfOrNull { it.categories } ?: return (1..count).map { it.toString() }
        val labels = (1..count).map { it.toString() }.toMutableList()
        for (point in cache.points) {
            if (point.index in 0 until count) labels[point.index] = point.text.ifBlank { (point.index + 1).toString() }
        }
        return labels
    }

    private fun cacheArray(cache: ChartCache?, count: Int): Array<Double?> {
        val size = if (count == Int.MAX_VALUE) (cache?.points?.maxOfOrNull { it.index + 1 } ?: 0) else count
        val result = arrayOfNulls<Double>(size)
        cache?.points?.forEach { point -> if (point.index in 0 until size) result[point.index] = point.number }
        return result
    }

    private fun categoryTotals(values: List<Array<Double?>>, count: Int): DoubleArray {
        val totals = DoubleArray(count)
        for (c in 0 until count) {
            for (series in values) totals[c] += abs(series[c] ?: 0.0)
        }
        return totals
    }

    private fun totals(values: List<Array<Double?>>, stacked: Boolean, count: Int): List<Double> {
        if (!stacked) return values.flatMap { it.filterNotNull() }
        val totals = ArrayList<Double>(count * 2)
        for (c in 0 until count) {
            var positive = 0.0
            var negative = 0.0
            for (series in values) {
                val value = series[c] ?: continue
                if (value >= 0.0) positive += value else negative += value
            }
            totals += positive
            totals += negative
        }
        return totals
    }

    private fun valueRange(values: List<Double>, axis: ChartAxis?): ValueRange {
        val scaling = axis?.scaling
        var minValue = scaling?.min ?: (values.minOrNull() ?: 0.0)
        var maxValue = scaling?.max ?: (values.maxOrNull() ?: 1.0)
        if (scaling?.min == null) minValue = min(minValue, 0.0)
        if (scaling?.max == null) maxValue = max(maxValue, 0.0)
        if (maxValue - minValue < 1e-9) {
            maxValue += 1.0
            if (minValue < 0.0) minValue -= 1.0
        }
        val step = scaling?.majorUnit?.takeIf { it > 0.0 } ?: niceStep((maxValue - minValue) / 5.0)
        return ValueRange(minValue, maxValue, step)
    }

    private fun niceStep(raw: Double): Double {
        if (raw <= 0.0 || !raw.isFinite()) return 1.0
        val exponent = floor(log10(raw))
        val base = 10.0.pow(exponent)
        val fraction = raw / base
        val nice = when {
            fraction <= 1.0 -> 1.0
            fraction <= 2.0 -> 2.0
            fraction <= 5.0 -> 5.0
            else -> 10.0
        }
        return nice * base
    }

    private class ValueRange(val min: Double, val max: Double, val step: Double) {

        fun fraction(value: Double): Double = ((value - min) / (max - min)).coerceIn(-2.0, 3.0)

        fun ticks(): List<Double> {
            if (step <= 0.0) return emptyList()
            val result = ArrayList<Double>()
            var value = ceil(min / step) * step
            var guard = 0
            while (value <= max + step * 1e-6 && guard < 200) {
                result += value
                value += step
                guard++
            }
            return result
        }
    }

    private fun categoryAxis(chart: PptxChart): ChartAxis? =
        chart.plotArea.axes.firstOrNull { it.kind == AxisKind.CATEGORY || it.kind == AxisKind.DATE }

    private fun valueAxis(chart: PptxChart): ChartAxis? =
        chart.plotArea.axes.firstOrNull { it.kind == AxisKind.VALUE && it.position != "b" }
            ?: chart.plotArea.axes.firstOrNull { it.kind == AxisKind.VALUE }

    private fun catLength(rect: SceneRect, horizontal: Boolean): Double = if (horizontal) rect.height else rect.width

    private fun categoryX(rect: SceneRect, index: Int, count: Int, axis: ChartAxis?): Double {
        val slot = rect.width / count
        val reversed = axis?.scaling?.orientation == AxisOrientation.MAX_MIN
        return if (reversed) rect.right - slot * (index + 0.5) else rect.x + slot * (index + 0.5)
    }

    private fun valueX(rect: SceneRect, range: ValueRange, value: Double, axis: ChartAxis?): Double {
        val fraction = range.fraction(value)
        return if (axis?.scaling?.orientation == AxisOrientation.MAX_MIN) {
            rect.right - rect.width * fraction
        } else {
            rect.x + rect.width * fraction
        }
    }

    private fun valueY(rect: SceneRect, range: ValueRange, value: Double, axis: ChartAxis?): Double {
        val fraction = range.fraction(value)
        return if (axis?.scaling?.orientation == AxisOrientation.MAX_MIN) {
            rect.y + rect.height * fraction
        } else {
            rect.bottom - rect.height * fraction
        }
    }

    private fun rectanglePoints(rect: SceneRect): List<ScenePoint> = listOf(
        ScenePoint(rect.x, rect.y),
        ScenePoint(rect.right, rect.y),
        ScenePoint(rect.right, rect.bottom),
        ScenePoint(rect.x, rect.bottom),
    )

    private fun filled(rect: SceneRect, fill: ScenePaint): ChartShape =
        ChartShape(rectanglePoints(rect), fill, null)

    private fun line(x1: Double, y1: Double, x2: Double, y2: Double, paint: ScenePaint): ChartShape =
        ChartShape(
            listOf(ScenePoint(x1, y1), ScenePoint(x2, y2)),
            fill = null,
            line = SceneLine(LINE_EMU, paint, null),
            closed = false,
        )
}
