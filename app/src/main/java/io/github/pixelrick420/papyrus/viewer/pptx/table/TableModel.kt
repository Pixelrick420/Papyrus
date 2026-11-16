package io.github.pixelrick420.papyrus.viewer.pptx.table

import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import io.github.pixelrick420.papyrus.viewer.pptx.XNode
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextBody

internal data class TableInsets(
    val left: Double = 91_440.0,
    val top: Double = 45_720.0,
    val right: Double = 91_440.0,
    val bottom: Double = 45_720.0,
)

internal enum class TableCellAnchor { TOP, MIDDLE, BOTTOM }

internal class TableBorder(val widthEmu: Double, val paint: ScenePaint?)

internal class TableBorders(
    val left: TableBorder? = null,
    val right: TableBorder? = null,
    val top: TableBorder? = null,
    val bottom: TableBorder? = null,
    val topLeftToBottomRight: TableBorder? = null,
    val bottomLeftToTopRight: TableBorder? = null,
)

internal enum class TableStyleBand { WHOLE_TABLE, BAND1_H, BAND2_H, BAND1_V, BAND2_V, FIRST_ROW, LAST_ROW, FIRST_COL, LAST_COL, NORTH_WEST, NORTH_EAST, SOUTH_WEST, SOUTH_EAST }

internal class TableStyleFlags(
    val firstRow: Boolean = false,
    val lastRow: Boolean = false,
    val firstCol: Boolean = false,
    val lastCol: Boolean = false,
    val bandRow: Boolean = false,
    val bandCol: Boolean = false,
)

internal class TableCellProperties(
    val margins: TableInsets = TableInsets(),
    val anchor: TableCellAnchor = TableCellAnchor.TOP,
    val borders: TableBorders = TableBorders(),
    val fill: ScenePaint? = null,
)

internal class TableCell(
    val textBody: XNode?,
    val gridSpan: Int = 1,
    val rowSpan: Int = 1,
    val horizontalMerge: Boolean = false,
    val verticalMerge: Boolean = false,
    val properties: TableCellProperties = TableCellProperties(),
    var laidOutText: TextBody? = null,
)

internal class TableRow(
    val heightEmu: Double,
    val cells: List<TableCell>,
    var drawnHeightEmu: Double = heightEmu,
)

internal class TableGrid(val columnWidths: List<Double>)

internal class PptxTable(
    val grid: TableGrid,
    val rows: List<TableRow>,
    val properties: TableStyleFlags = TableStyleFlags(),
    val styleId: String? = null,
    var layout: TableLayoutResult? = null,
)

internal class TableLayoutResult(
    val rowHeightsEmu: List<Double>,
    val totalHeightEmu: Double,
    val cellFrames: Map<Pair<Int, Int>, SceneRect>,
)
