package io.github.pixelrick420.papyrus.viewer.pptx.chart

import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.SceneLine
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePoint
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import io.github.pixelrick420.papyrus.viewer.pptx.XNode

/** One filled or stroked outline, in the chart's own coordinate space. */
internal class ChartShape(
    val points: List<ScenePoint>,
    val fill: ScenePaint? = null,
    val line: SceneLine? = null,
    val closed: Boolean = true,
)

/** A text body shaped like `a:txBody`, so the slide text engine lays it out unchanged. */
internal class ChartText(val body: XNode, val frame: SceneRect)

internal class ChartDrawing(
    val shapes: List<ChartShape> = emptyList(),
    val texts: List<ChartText> = emptyList(),
)

/** The theme-resolved colours and paint lookups a chart needs before it can be drawn. */
internal class ChartStyle(
    val palette: List<Rgba>,
    val text: Rgba,
    val grid: Rgba,
    val axis: Rgba,
    val fillOf: (XNode?) -> ScenePaint?,
    val lineOf: (XNode?) -> SceneLine?,
)
