package io.github.pixelrick420.papyrus.viewer.pptx.table

import io.github.pixelrick420.papyrus.viewer.pptx.ColorModel
import io.github.pixelrick420.papyrus.viewer.pptx.ColorSource
import io.github.pixelrick420.papyrus.viewer.pptx.ColorValue
import io.github.pixelrick420.papyrus.viewer.pptx.Fills
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlNamespace
import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import io.github.pixelrick420.papyrus.viewer.pptx.Rgba
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import io.github.pixelrick420.papyrus.viewer.pptx.XNode

internal object TableParser {

    fun parse(table: XNode, limits: PptxLimits): PptxTable? {
        val grid = table.child(OoxmlNamespace.A, "tblGrid") ?: return null
        val columns = grid.childrenOf(OoxmlNamespace.A, "gridCol").mapNotNull { it.attribute("w")?.toDoubleOrNull() }
        if (columns.isEmpty() || columns.size > limits.maxTableColumns) return null
        val rowNodes = table.childrenOf(OoxmlNamespace.A, "tr")
        if (rowNodes.isEmpty() || rowNodes.size > limits.maxTableRows) return null
        val rows = ArrayList<TableRow>(rowNodes.size)
        var cellCount = 0
        for (row in rowNodes) {
            val height = row.attribute("h")?.toDoubleOrNull() ?: 0.0
            val cells = ArrayList<TableCell>()
            for (cell in row.childrenOf(OoxmlNamespace.A, "tc")) {
                cellCount++
                if (cellCount > limits.maxTableCells) return null
                cells += parseCell(cell, limits)
            }
            rows += TableRow(height, cells)
        }
        val tblPr = table.child(OoxmlNamespace.A, "tblPr")
        return PptxTable(
            grid = TableGrid(columns),
            rows = rows,
            properties = parseFlags(tblPr),
            styleId = tblPr?.child(OoxmlNamespace.A, "tableStyleId")?.text?.trim()?.takeIf { it.isNotEmpty() },
        )
    }

    private fun parseFlags(tblPr: XNode?): TableStyleFlags = TableStyleFlags(
        firstRow = on(tblPr?.attribute("firstRow")),
        lastRow = on(tblPr?.attribute("lastRow")),
        firstCol = on(tblPr?.attribute("firstCol")),
        lastCol = on(tblPr?.attribute("lastCol")),
        bandRow = on(tblPr?.attribute("bandRow")),
        bandCol = on(tblPr?.attribute("bandCol")),
    )

    private fun parseCell(cell: XNode, limits: PptxLimits): TableCell {
        val tcPr = cell.child(OoxmlNamespace.A, "tcPr")
        return TableCell(
            textBody = cell.child(OoxmlNamespace.A, "txBody"),
            gridSpan = cell.attribute("gridSpan")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            rowSpan = cell.attribute("rowSpan")?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            horizontalMerge = on(cell.attribute("hMerge")),
            verticalMerge = on(cell.attribute("vMerge")),
            properties = parseCellProperties(tcPr, limits),
        )
    }

    private fun parseCellProperties(tcPr: XNode?, limits: PptxLimits): TableCellProperties {
        if (tcPr == null) return TableCellProperties()
        return TableCellProperties(
            margins = TableInsets(
                left = tcPr.attribute("marL")?.toDoubleOrNull() ?: 91_440.0,
                right = tcPr.attribute("marR")?.toDoubleOrNull() ?: 91_440.0,
                top = tcPr.attribute("marT")?.toDoubleOrNull() ?: 45_720.0,
                bottom = tcPr.attribute("marB")?.toDoubleOrNull() ?: 45_720.0,
            ),
            anchor = when (tcPr.attribute("anchor")) {
                "ctr" -> TableCellAnchor.MIDDLE
                "b" -> TableCellAnchor.BOTTOM
                else -> TableCellAnchor.TOP
            },
            borders = parseBorders(tcPr, limits),
            fill = parseFill(tcPr, limits),
        )
    }

    private fun parseBorders(tcPr: XNode, limits: PptxLimits): TableBorders = TableBorders(
        left = parseBorder(tcPr.child(OoxmlNamespace.A, "lnL"), limits),
        right = parseBorder(tcPr.child(OoxmlNamespace.A, "lnR"), limits),
        top = parseBorder(tcPr.child(OoxmlNamespace.A, "lnT"), limits),
        bottom = parseBorder(tcPr.child(OoxmlNamespace.A, "lnB"), limits),
        topLeftToBottomRight = parseBorder(tcPr.child(OoxmlNamespace.A, "lnTlToBr"), limits),
        bottomLeftToTopRight = parseBorder(tcPr.child(OoxmlNamespace.A, "lnBlToTr"), limits),
    )

    private fun parseBorder(line: XNode?, limits: PptxLimits): TableBorder? {
        line ?: return null
        if (line.child(OoxmlNamespace.A, "noFill") != null) return TableBorder(0.0, ScenePaint.None)
        val width = line.attribute("w")?.toDoubleOrNull() ?: 12700.0
        val fill = parseFill(line, limits)
        return TableBorder(width, fill)
    }

    private fun parseFill(container: XNode, limits: PptxLimits): ScenePaint? {
        val fill = container.children.firstOrNull {
            it.name.namespace == OoxmlNamespace.A && it.name.localName in FILL_TAGS
        } ?: return null
        return Fills.paintOf(fill, null, DIRECT_COLORS, limits.maxGradientStops.coerceAtLeast(0))
    }

    private fun on(value: String?): Boolean = value == "1" || value.equals("true", ignoreCase = true)

    private val FILL_TAGS = setOf("noFill", "solidFill", "gradFill", "blipFill", "pattFill", "grpFill")

    private val DIRECT_COLORS = ColorModel(null, io.github.pixelrick420.papyrus.viewer.pptx.ColorMap())
}
