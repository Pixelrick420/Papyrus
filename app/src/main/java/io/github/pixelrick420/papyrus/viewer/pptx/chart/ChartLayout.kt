package io.github.pixelrick420.papyrus.viewer.pptx.chart

import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect

internal object ChartLayout {

    fun layout(chart: PptxChart, bounds: SceneRect): ChartLayoutResult {
        val titleRect = layoutTitle(chart.title, bounds)
        val content = subtractTitle(bounds, titleRect, chart.title?.overlay == true)
        val legendRect = layoutLegend(chart.legend, content)
        val plotContent = subtractLegend(content, legendRect, chart.legend?.overlay == true, chart.legend?.position)
        val plotRect = layoutPlotArea(chart.plotArea.manualLayout, plotContent)
        return ChartLayoutResult(plotArea = plotRect, legend = legendRect, title = titleRect)
    }

    private fun layoutTitle(title: ChartTitle?, bounds: SceneRect): SceneRect? {
        title ?: return null
        val manual = title.manualLayout?.let { rectFromManual(it, bounds) }
        if (manual != null) return manual
        val height = (bounds.height * 0.12).coerceAtLeast(bounds.height * 0.08).coerceAtMost(bounds.height * 0.2)
        return SceneRect(bounds.x, bounds.y, bounds.width, height)
    }

    private fun subtractTitle(bounds: SceneRect, title: SceneRect?, overlay: Boolean): SceneRect {
        if (title == null || overlay) return bounds
        val top = (title.y + title.height - bounds.y).coerceAtLeast(0.0)
        val height = (bounds.height - top).coerceAtLeast(0.0)
        return SceneRect(bounds.x, bounds.y + top, bounds.width, height)
    }

    private fun layoutLegend(legend: ChartLegend?, bounds: SceneRect): SceneRect? {
        legend ?: return null
        if (legend.position == LegendPosition.NONE) return null
        legend.manualLayout?.let { rectFromManual(it, bounds) }?.let { return it }
        val w = bounds.width
        val h = bounds.height
        return when (legend.position) {
            LegendPosition.RIGHT, LegendPosition.TOP_RIGHT -> SceneRect(bounds.x + w * 0.8, bounds.y + h * 0.1, w * 0.18, h * 0.8)
            LegendPosition.LEFT -> SceneRect(bounds.x + w * 0.02, bounds.y + h * 0.1, w * 0.18, h * 0.8)
            LegendPosition.TOP -> SceneRect(bounds.x + w * 0.1, bounds.y, w * 0.8, h * 0.12)
            LegendPosition.BOTTOM -> SceneRect(bounds.x + w * 0.1, bounds.y + h * 0.88, w * 0.8, h * 0.12)
            LegendPosition.NONE -> null
        }
    }

    private fun subtractLegend(bounds: SceneRect, legend: SceneRect?, overlay: Boolean, position: LegendPosition?): SceneRect {
        if (legend == null || overlay || position == null || position == LegendPosition.NONE) return bounds
        return when (position) {
            LegendPosition.RIGHT, LegendPosition.TOP_RIGHT -> {
                val width = (legend.x - bounds.x).coerceAtLeast(0.0)
                SceneRect(bounds.x, bounds.y, width, bounds.height)
            }
            LegendPosition.LEFT -> {
                val consumed = (legend.x + legend.width - bounds.x).coerceAtLeast(0.0)
                SceneRect(bounds.x + consumed, bounds.y, (bounds.width - consumed).coerceAtLeast(0.0), bounds.height)
            }
            LegendPosition.TOP -> {
                val consumed = (legend.y + legend.height - bounds.y).coerceAtLeast(0.0)
                SceneRect(bounds.x, bounds.y + consumed, bounds.width, (bounds.height - consumed).coerceAtLeast(0.0))
            }
            LegendPosition.BOTTOM -> {
                val height = (legend.y - bounds.y).coerceAtLeast(0.0)
                SceneRect(bounds.x, bounds.y, bounds.width, height)
            }
            LegendPosition.NONE -> bounds
        }
    }

    private fun layoutPlotArea(manual: ChartManualLayout?, bounds: SceneRect): SceneRect {
        manual?.let { rectFromManual(it, bounds) }?.let { return it }
        val insetX = bounds.width * 0.1
        val insetY = bounds.height * 0.1
        return SceneRect(
            bounds.x + insetX,
            bounds.y + insetY,
            (bounds.width - insetX * 1.2).coerceAtLeast(bounds.width * 0.5),
            (bounds.height - insetY * 1.4).coerceAtLeast(bounds.height * 0.5),
        )
    }

    private fun rectFromManual(layout: ChartManualLayout, bounds: SceneRect): SceneRect? {
        val x = resolveManual(layout.x, layout.xMode, bounds.x, bounds.width) ?: return null
        val y = resolveManual(layout.y, layout.yMode, bounds.y, bounds.height) ?: return null
        val w = resolveManual(layout.w, layout.wMode, 0.0, bounds.width) ?: return null
        val h = resolveManual(layout.h, layout.hMode, 0.0, bounds.height) ?: return null
        return SceneRect(x, y, w, h)
    }

    private fun resolveManual(value: Double?, mode: String?, origin: Double, size: Double): Double? {
        value ?: return null
        return when (mode) {
            "edge" -> origin + value
            "factor", null -> origin + value * size
            else -> origin + value * size
        }
    }
}
