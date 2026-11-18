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
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal object ChartBuilder {

    private const val EMU_PER_POINT = 12_700.0
    private const val TITLE_SIZE_PT = 14.0
    private const val LABEL_SIZE_PT = 9.0
    private const val LEGEND_SIZE_PT = 9.0
    private const val TICK_SIZE_PT = 9.0
    private const val LINE_EMU = 9_525.0

    fun build(chart: PptxChart, bounds: SceneRect, baseStyle: ChartStyle, description: String?): ChartDrawing {
        if (bounds.width <= 0.0 || bounds.height <= 0.0) return ChartDrawing()
        val style = chart.style?.let { styleFor(it, baseStyle) } ?: baseStyle
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
            ChartType.BUBBLE -> bubble(chart, layout.plotArea, style, shapes, texts)
            ChartType.RADAR -> radar(chart, layout.plotArea, style, shapes, texts)
            ChartType.PIE, ChartType.DOUGHNUT -> pie(chart, layout.plotArea, style, shapes, texts)
            ChartType.COMBO, ChartType.STOCK -> combined(chart, layout.plotArea, style, shapes, texts)
            ChartType.OTHER -> Unit
        }
        legend(chart, layout.legend, style, shapes, texts)
        return ChartDrawing(shapes, texts)
    }

    private fun styleFor(index: Int, base: ChartStyle): ChartStyle {
        val tone = (index - 1).coerceAtLeast(0) % 7
        val lighten = when {
            tone < 3 -> 0.28
            tone > 4 -> -0.18
            else -> 0.0
        }
        val alpha = if (tone == 6) 200 else 255
        return ChartStyle(
            palette = base.palette.map { adjust(it, lighten, alpha) },
            text = base.text,
            grid = base.grid,
            axis = base.axis,
            fillOf = base.fillOf,
            lineOf = base.lineOf,
        )
    }

    private fun adjust(color: Rgba, lighten: Double, alpha: Int): Rgba {
        fun channel(value: Int): Int = if (lighten >= 0.0) {
            value + ((255 - value) * lighten).toInt()
        } else {
            (value * (1.0 + lighten)).toInt().coerceIn(0, 255)
        }
        return Rgba(channel(color.red), channel(color.green), channel(color.blue), alpha)
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
            refinements(
                ser,
                (0 until count).map { it.toDouble() },
                values[s].toList(),
                { index, v ->
                    if (horizontal) {
                        ScenePoint(valueX(rect, range, v, valueAxis), rect.y + (index + 0.5) * slot)
                    } else {
                        ScenePoint(rect.x + (index + 0.5) * slot, valueY(rect, range, v, valueAxis))
                    }
                },
                style,
                shapes,
                texts,
            )
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
            refinements(
                ser,
                (0 until count).map { it.toDouble() },
                values[s].toList(),
                { index, v -> ScenePoint(categoryX(rect, index, count, categoryAxis), valueY(rect, range, v, valueAxis)) },
                style,
                shapes,
                texts,
            )
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
            refinements(
                ser,
                (0 until count).map { it.toDouble() },
                values[s].toList(),
                { index, v -> ScenePoint(categoryX(rect, index, count, categoryAxis), valueY(rect, range, v, valueAxis)) },
                style,
                shapes,
                texts,
            )
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
            refinements(
                ser,
                xValues[s].filterNotNull(),
                yValues[s].toList(),
                { index, v ->
                    val x = xValues[s].filterNotNull().getOrNull(index) ?: 0.0
                    ScenePoint(valueX(rect, rangeX, x, xAxis), valueY(rect, rangeY, v, yAxis))
                },
                style,
                shapes,
                texts,
            )
        }
    }

    private fun combined(
        chart: PptxChart,
        rect: SceneRect,
        style: ChartStyle,
        shapes: MutableList<ChartShape>,
        texts: MutableList<ChartText>,
    ) {
        val series = chart.plotArea.series
        if (series.isEmpty()) return
        val count = categoryCount(series)
        if (count <= 0) return
        val category = categoryAxis(chart)
        val value = valueAxis(chart)
        val secondaryAxis = chart.plotArea.axes.firstOrNull {
            it.kind == AxisKind.VALUE && it.group == AxisGroup.SECONDARY
        }
        val primarySeries = series.filter { it.axisGroup == AxisGroup.PRIMARY }
        val secondarySeries = series.filter { it.axisGroup == AxisGroup.SECONDARY }
        val primaryRange = valueRange(
            primarySeries.flatMap { ser ->
                (ser.values ?: ser.closeValues ?: ser.highValues)?.let { cacheArray(it, count).filterNotNull() }.orEmpty()
            },
            value,
        )
        val secondaryRange = secondarySeries.takeIf { it.isNotEmpty() }?.let {
            valueRange(it.flatMap { cacheArray(it.values, count).filterNotNull() }, secondaryAxis)
        }
        axes(chart, rect, primaryRange, horizontal = false, style, shapes)
        categoryTicks(category, categoryLabels(series, count), rect, count, false, style, texts)
        valueTicks(value, rect, primaryRange, horizontal = false, style, texts)
        if (secondaryRange != null && secondaryAxis != null) {
            shapes += line(rect.right, rect.y, rect.right, rect.bottom, ScenePaint.Solid(style.axis))
            secondaryTicks(secondaryAxis, rect, secondaryRange, style, texts)
        }
        val barSeries = series.filter { it.type == ChartType.BAR || it.type == ChartType.COLUMN }
        val slot = rect.width / count
        val gap = ((chart.gapWidth ?: 150).coerceIn(0, 500)) / 100.0
        val barWidth = slot / (1.0 + gap) / barSeries.size.coerceAtLeast(1)
        for ((s, ser) in barSeries.withIndex()) {
            val range = if (ser.axisGroup == AxisGroup.SECONDARY) secondaryRange ?: primaryRange else primaryRange
            val axis = if (ser.axisGroup == AxisGroup.SECONDARY) secondaryAxis ?: value else value
            val values = cacheArray(ser.values, count)
            for (c in 0 until count) {
                val v = values[c] ?: continue
                val x = rect.x + c * slot + (slot - barWidth * barSeries.size) / 2.0 + s * barWidth
                val y1 = valueY(rect, range, 0.0, axis)
                val y2 = valueY(rect, range, v, axis)
                val bar = SceneRect(x, min(y1, y2), barWidth, abs(y2 - y1))
                if (bar.height <= 0.0) continue
                shapes += ChartShape(rectanglePoints(bar), seriesPaint(ser, c, false, style), seriesLine(ser, c, style))
            }
        }
        for (ser in series.filter { it.type == ChartType.STOCK }) {
            val range = if (ser.axisGroup == AxisGroup.SECONDARY) secondaryRange ?: primaryRange else primaryRange
            val axis = if (ser.axisGroup == AxisGroup.SECONDARY) secondaryAxis ?: value else value
            stockSeries(ser, chart.upDownBars, category, rect, range, axis, count, style, shapes)
        }
        for (ser in series.filter {
            it.type == ChartType.LINE || it.type == ChartType.AREA || it.type == ChartType.RADAR
        }) {
            val range = if (ser.axisGroup == AxisGroup.SECONDARY) secondaryRange ?: primaryRange else primaryRange
            val axis = if (ser.axisGroup == AxisGroup.SECONDARY) secondaryAxis ?: value else value
            val values = cacheArray(ser.values, count)
            val points = ArrayList<ScenePoint>()
            for (c in 0 until count) {
                val v = values[c] ?: continue
                points += ScenePoint(categoryX(rect, c, count, category), valueY(rect, range, v, axis))
            }
            if (points.isEmpty()) continue
            val color = seriesColor(ser, -1, false, style)
            val line = seriesLine(ser, -1, style) ?: defaultLine(color)
            if (points.size > 1) shapes += ChartShape(points, fill = null, line = line, closed = false)
            if (ser.type == ChartType.AREA) {
                val baseline = valueY(rect, range, 0.0, axis)
                shapes += ChartShape(
                    points + ScenePoint(points.last().x, baseline) + ScenePoint(points.first().x, baseline),
                    explicitFill(ser, -1, style) ?: ScenePaint.Solid(Rgba(color.red, color.green, color.blue, 120)),
                    line,
                )
            }
            if (ser.marker) {
                for (point in points) shapes += marker(point, seriesPaint(ser, -1, false, style))
            }
            refinements(
                ser,
                (0 until count).map { it.toDouble() },
                values.toList(),
                { index, v -> ScenePoint(categoryX(rect, index, count, category), valueY(rect, range, v, axis)) },
                style,
                shapes,
                texts,
            )
        }
    }

    private fun stockSeries(
        series: ChartSeries,
        upDownBars: ChartUpDownBars?,
        category: ChartAxis?,
        rect: SceneRect,
        range: ValueRange,
        axis: ChartAxis?,
        count: Int,
        style: ChartStyle,
        shapes: MutableList<ChartShape>,
    ) {
        val highs = cacheArray(series.highValues, count)
        val lows = cacheArray(series.lowValues, count)
        val opens = cacheArray(series.openValues, count)
        val closes = cacheArray(series.closeValues ?: series.values, count)
        val color = seriesColor(series, -1, false, style)
        val paint = SceneLine(LINE_EMU, ScenePaint.Solid(color), null)
        for (c in 0 until count) {
            val high = highs.getOrNull(c)
            val low = lows.getOrNull(c)
            val open = opens.getOrNull(c)
            val close = closes.getOrNull(c)
            val x = categoryX(rect, c, count, category)
            if (high != null && low != null) {
                shapes += ChartShape(
                    listOf(
                        ScenePoint(x, valueY(rect, range, high, axis)),
                        ScenePoint(x, valueY(rect, range, low, axis)),
                    ),
                    fill = null,
                    line = paint,
                    closed = false,
                )
            }
            if (open != null && close != null && upDownBars?.hide != true) {
                val up = close >= open
                val color = if (up) Rgba(0x00, 0xB0, 0x50) else Rgba(0xC0, 0x00, 0x00)
                val explicit = if (up) upDownBars?.upBars else upDownBars?.downBars
                val paint = explicit?.let { style.fillOf(it) } ?: ScenePaint.Solid(color)
                val gap = ((upDownBars?.gapWidth ?: 150).coerceIn(0, 500)) / 100.0
                val width = (rect.width / count / (1.0 + gap)).coerceAtLeast(LINE_EMU)
                val bar = SceneRect(
                    x - width / 2.0,
                    min(valueY(rect, range, open, axis), valueY(rect, range, close, axis)),
                    width,
                    abs(valueY(rect, range, close, axis) - valueY(rect, range, open, axis)).coerceAtLeast(LINE_EMU),
                )
                shapes += ChartShape(rectanglePoints(bar), paint, null)
            }
        }
    }

    private fun secondaryTicks(
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
            val y = valueY(rect, range, tick, axis)
            texts += ChartText(
                ChartTextFactory.body(label, TICK_SIZE_PT, style.text, align = "l"),
                SceneRect(rect.right + 2 * EMU_PER_POINT, y - 7 * EMU_PER_POINT, 58 * EMU_PER_POINT, 14 * EMU_PER_POINT),
            )
        }
    }

    private fun bubble(
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
        val xs = series.map { cacheArray(it.xValues ?: it.categories, Int.MAX_VALUE) }
        val ys = series.map { cacheArray(it.yValues ?: it.values, Int.MAX_VALUE) }
        val sizes = series.map { cacheArray(it.bubbleSizes, Int.MAX_VALUE) }
        val allSizes = sizes.flatMap { it.filterNotNull() }
        val minSize = allSizes.minOrNull() ?: 0.0
        val maxSize = allSizes.maxOrNull() ?: 1.0
        val rangeX = valueRange(xs.flatMap { it.filterNotNull() }, xAxis)
        val rangeY = valueRange(ys.flatMap { it.filterNotNull() }, yAxis)
        axes(chart, rect, rangeY, horizontal = false, style, shapes)
        valueTicks(yAxis, rect, rangeY, horizontal = false, style, texts)
        scatterTicks(xAxis, rect, rangeX, style, texts)
        for ((s, ser) in series.withIndex()) {
            val count = minOf(xs[s].size, ys[s].size)
            for (i in 0 until count) {
                val x = xs[s][i] ?: continue
                val y = ys[s][i] ?: continue
                val fraction = sizes[s].getOrNull(i)?.let { size ->
                    if (maxSize - minSize < 1e-9) 0.6 else 0.3 + 0.7 * (size - minSize) / (maxSize - minSize)
                } ?: 0.3
                val radius = fraction * min(rect.width, rect.height) / 14.0
                val center = ScenePoint(valueX(rect, rangeX, x, xAxis), valueY(rect, rangeY, y, yAxis))
                shapes += marker(center, seriesPaint(ser, i, true, style), radius)
            }
            refinements(
                ser,
                xs[s].filterNotNull(),
                ys[s].toList(),
                { index, v ->
                    val x = xs[s].filterNotNull().getOrNull(index) ?: 0.0
                    ScenePoint(valueX(rect, rangeX, x, xAxis), valueY(rect, rangeY, v, yAxis))
                },
                style,
                shapes,
                texts,
            )
        }
    }

    private fun radar(
        chart: PptxChart,
        rect: SceneRect,
        style: ChartStyle,
        shapes: MutableList<ChartShape>,
        texts: MutableList<ChartText>,
    ) {
        val series = chart.plotArea.series
        val count = categoryCount(series)
        if (count < 3) return
        val values = series.map { cacheArray(it.values, count) }
        val peak = values.flatMap { it.filterNotNull().map(::abs) }.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
        val radius = min(rect.width, rect.height) / 2.0 * 0.82
        if (radius <= 0.0) return
        val center = ScenePoint(rect.x + rect.width / 2.0, rect.y + rect.height / 2.0)
        for (ring in 1..4) {
            val r = radius * ring / 4.0
            shapes += ChartShape(
                spokePoints(center, r, count),
                fill = null,
                line = SceneLine(LINE_EMU, ScenePaint.Solid(style.grid), null),
            )
        }
        for (i in 0 until count) {
            val angle = spokeAngle(i, count)
            shapes += line(
                center.x,
                center.y,
                center.x + sin(angle) * radius,
                center.y - cos(angle) * radius,
                ScenePaint.Solid(style.axis),
            )
        }
        val labels = categoryLabels(series, count)
        for (i in 0 until count) {
            val angle = spokeAngle(i, count)
            val frame = SceneRect(
                center.x + sin(angle) * radius * 1.06 - 20 * EMU_PER_POINT,
                center.y - cos(angle) * radius * 1.06 - 7 * EMU_PER_POINT,
                40 * EMU_PER_POINT,
                14 * EMU_PER_POINT,
            )
            texts += ChartText(ChartTextFactory.body(labels[i], TICK_SIZE_PT, style.text, align = "ctr"), frame)
        }
        for ((s, ser) in series.withIndex()) {
            val points = ArrayList<ScenePoint>()
            for (i in 0 until count) {
                val value = values[s][i] ?: continue
                val r = radius * (abs(value) / peak).coerceIn(0.0, 1.0)
                val angle = spokeAngle(i, count)
                points += ScenePoint(center.x + sin(angle) * r, center.y - cos(angle) * r)
            }
            if (points.isEmpty()) continue
            val color = seriesColor(ser, -1, false, style)
            val line = seriesLine(ser, -1, style) ?: defaultLine(color)
            val fill = if (chart.radarStyle == RadarStyle.FILLED) {
                explicitFill(ser, -1, style) ?: ScenePaint.Solid(Rgba(color.red, color.green, color.blue, 120))
            } else {
                null
            }
            shapes += ChartShape(points, fill, line)
            if (chart.radarStyle == RadarStyle.MARKER) {
                for (point in points) shapes += marker(point, seriesPaint(ser, -1, false, style))
            }
        }
    }

    private fun refinements(
        series: ChartSeries,
        xs: List<Double>,
        values: List<Double?>,
        pointAt: (Int, Double) -> ScenePoint,
        style: ChartStyle,
        shapes: MutableList<ChartShape>,
        texts: MutableList<ChartText>,
    ) {
        val pairs = xs.zip(values).mapNotNull { (x, y) -> y?.let { x to it } }
        for (trend in series.trendlines) {
            val fit = fit(trend, pairs) ?: continue
            val points = pairs.mapIndexed { index, (x, _) -> pointAt(index, fit(x)) }
            if (points.size < 2) continue
            val color = seriesColor(series, -1, false, style)
            shapes += ChartShape(
                points,
                fill = null,
                line = SceneLine(2 * EMU_PER_POINT, ScenePaint.Solid(color), "dash"),
                closed = false,
            )
            val last = points.last()
            val label = buildString {
                if (trend.displayEquation) append(trend.equation(pairs))
                if (trend.displayRSqr) {
                    if (isNotEmpty()) append('\n')
                    append("R² = ${trend.rSquared(pairs)}")
                }
            }
            if (label.isNotBlank()) {
                texts += ChartText(
                    ChartTextFactory.body(label, LABEL_SIZE_PT, style.text),
                    SceneRect(last.x - 60 * EMU_PER_POINT, last.y - 30 * EMU_PER_POINT, 120 * EMU_PER_POINT, 26 * EMU_PER_POINT),
                )
            }
        }
        for (bars in series.errorBars) {
            for ((index, value) in values.withIndex()) {
                val y = value ?: continue
                val magnitudes = values.filterNotNull()
                val spread = when (bars.type) {
                    ErrorBarType.FIXED_VALUE -> bars.value ?: 0.0
                    ErrorBarType.PERCENT -> abs(y) * (bars.value ?: 0.0) / 100.0
                    ErrorBarType.STANDARD_DEVIATION -> deviation(magnitudes) * (bars.value ?: 1.0)
                    ErrorBarType.STANDARD_ERROR -> deviation(magnitudes) / sqrt(magnitudes.size.toDouble().coerceAtLeast(1.0))
                    ErrorBarType.CUSTOM -> 0.0
                }
                val upper = if (bars.type == ErrorBarType.CUSTOM) {
                    bars.plus?.points?.firstOrNull { it.index == index }?.number
                } else if (bars.showPositive) spread else 0.0
                val lower = if (bars.type == ErrorBarType.CUSTOM) {
                    bars.minus?.points?.firstOrNull { it.index == index }?.number
                } else if (bars.showNegative) spread else 0.0
                val top = pointAt(index, y + (upper ?: 0.0))
                val bottom = pointAt(index, y - (lower ?: 0.0))
                val color = seriesColor(series, -1, false, style)
                shapes += ChartShape(
                    listOf(top, bottom),
                    fill = null,
                    line = SceneLine(EMU_PER_POINT, ScenePaint.Solid(color), null),
                    closed = false,
                )
                if (!bars.noEndCaps) {
                    val cap = 3 * EMU_PER_POINT
                    shapes += line(top.x - cap, top.y, top.x + cap, top.y, ScenePaint.Solid(color))
                    shapes += line(bottom.x - cap, bottom.y, bottom.x + cap, bottom.y, ScenePaint.Solid(color))
                }
            }
        }
    }

    private fun deviation(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    }

    private fun fit(trend: ChartTrendline, points: List<Pair<Double, Double>>): ((Double) -> Double)? {
        if (points.size < 2) return null
        when (trend.type) {
            TrendlineType.LINEAR -> {
                val (a, b) = linear(points.map { it.first to it.second }) ?: return null
                return { x: Double -> a + b * x }
            }
            TrendlineType.LOGARITHMIC -> {
                val logs = points.filter { it.first > 0.0 }.map { ln(it.first) to it.second }
                val (a, b) = linear(logs) ?: return null
                return { x: Double -> a + b * ln(x.coerceAtLeast(1e-9)) }
            }
            TrendlineType.EXPONENTIAL -> {
                val logs = points.filter { it.second > 0.0 }.map { it.first to ln(it.second) }
                val (a, b) = linear(logs) ?: return null
                return { x: Double -> exp(a + b * x) }
            }
            TrendlineType.POWER -> {
                val logs = points.filter { it.first > 0.0 && it.second > 0.0 }
                    .map { ln(it.first) to ln(it.second) }
                val (a, b) = linear(logs) ?: return null
                return { x: Double -> exp(a + b * ln(x.coerceAtLeast(1e-9))) }
            }
            TrendlineType.POLYNOMIAL -> return polynomial(points, trend.order.coerceIn(1, 6))
            TrendlineType.MOVING_AVERAGE -> {
                val period: Int = minOf(maxOf(trend.period, 1), points.size)
                return { x: Double ->
                    val index = points.indexOfFirst { it.first == x }.coerceAtLeast(0)
                    points.subList((index - period + 1).coerceAtLeast(0), index + 1).map { it.second }.average()
                }
            }
        }
    }

    private fun linear(points: List<Pair<Double, Double>>): Pair<Double, Double>? {
        if (points.size < 2) return null
        val mx = points.map { it.first }.average()
        val my = points.map { it.second }.average()
        val denominator = points.sumOf { (it.first - mx) * (it.first - mx) }
        if (denominator < 1e-12) return null
        val b = points.sumOf { (it.first - mx) * (it.second - my) } / denominator
        return my - b * mx to b
    }

    private fun polynomial(points: List<Pair<Double, Double>>, degree: Int): ((Double) -> Double)? {
        val size = degree + 1
        val matrix = Array(size) { DoubleArray(size + 1) }
        for (row in 0 until size) {
            for (column in 0 until size) {
                matrix[row][column] = points.sumOf { p -> Math.pow(p.first, (row + column).toDouble()) }
            }
            matrix[row][size] = points.sumOf { p -> p.second * Math.pow(p.first, row.toDouble()) }
        }
        for (column in 0 until size) {
            val pivot = (column until size).maxByOrNull { abs(matrix[it][column]) } ?: return null
            if (abs(matrix[pivot][column]) < 1e-12) return null
            val swap = matrix[column]
            matrix[column] = matrix[pivot]
            matrix[pivot] = swap
            val divisor = matrix[column][column]
            for (i in column..size) matrix[column][i] /= divisor
            for (row in 0 until size) {
                if (row == column) continue
                val factor = matrix[row][column]
                for (i in column..size) matrix[row][i] -= factor * matrix[column][i]
            }
        }
        val coefficients = DoubleArray(size) { matrix[it][size] }
        return { x -> (0 until size).sumOf { coefficients[it] * Math.pow(x, it.toDouble()) } }
    }

    private fun ChartTrendline.equation(points: List<Pair<Double, Double>>): String {
        val linear = linear(points.map { it.first to it.second })
        val slope = linear?.second ?: 0.0
        val intercept = linear?.first ?: points.first().second
        return "y = ${round(slope)}x + ${round(intercept)}"
    }

    private fun ChartTrendline.rSquared(points: List<Pair<Double, Double>>): String {
        val linear = linear(points.map { it.first to it.second }) ?: return "0.00"
        val mean = points.map { it.second }.average()
        val total = points.sumOf { (it.second - mean) * (it.second - mean) }
        if (total < 1e-12) return "1.00"
        val residual = points.sumOf { p ->
            val predicted = linear.first + linear.second * p.first
            (p.second - predicted) * (p.second - predicted)
        }
        return round((1.0 - residual / total).coerceIn(0.0, 1.0))
    }

    private fun round(value: Double): String = String.format("%.2f", value)

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
                val line = if (chart.type == ChartType.LINE || chart.type == ChartType.SCATTER || chart.type == ChartType.RADAR) {
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

    private fun marker(center: ScenePoint, fill: ScenePaint?): ChartShape =
        marker(center, fill, 4 * EMU_PER_POINT)

    private fun marker(center: ScenePoint, fill: ScenePaint?, radius: Double): ChartShape {
        val points = ArrayList<ScenePoint>(12)
        for (i in 0 until 12) {
            val angle = Math.toRadians(i * 30.0)
            points += ScenePoint(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
        }
        return ChartShape(points, fill ?: ScenePaint.Solid(Rgba.BLACK), SceneLine(EMU_PER_POINT, ScenePaint.Solid(Rgba.BLACK), null))
    }

    private fun spokeAngle(index: Int, count: Int): Double = 2.0 * Math.PI * index / count

    private fun spokePoints(center: ScenePoint, radius: Double, count: Int): List<ScenePoint> =
        (0 until count).map { i ->
            val angle = spokeAngle(i, count)
            ScenePoint(center.x + sin(angle) * radius, center.y - cos(angle) * radius)
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

    private fun categoryCount(series: List<ChartSeries>): Int = series.maxOfOrNull {
        maxOf(
            it.categories?.points?.size ?: 0,
            it.values?.points?.size ?: 0,
            it.xValues?.points?.size ?: 0,
            it.highValues?.points?.size ?: 0,
            it.lowValues?.points?.size ?: 0,
            it.openValues?.points?.size ?: 0,
            it.closeValues?.points?.size ?: 0,
        )
    } ?: 0

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
