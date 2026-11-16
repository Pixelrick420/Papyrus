package io.github.pixelrick420.papyrus.viewer.pptx.table

import io.github.pixelrick420.papyrus.viewer.pptx.ColorModel
import io.github.pixelrick420.papyrus.viewer.pptx.Fills
import io.github.pixelrick420.papyrus.viewer.pptx.OoxmlNamespace
import io.github.pixelrick420.papyrus.viewer.pptx.PptxLimits
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import io.github.pixelrick420.papyrus.viewer.pptx.XNode

internal class TableStylePart(
    val fill: ScenePaint? = null,
    val borders: TableBorders = TableBorders(),
    val textColor: io.github.pixelrick420.papyrus.viewer.pptx.Rgba? = null,
    val bold: Boolean? = null,
)

internal class TableStyleDef(val parts: Map<String, TableStylePart>)

internal class TableStyleSheet(
    val defaultStyleId: String?,
    val styles: Map<String, TableStyleDef>,
) {
    fun styleOf(id: String?): TableStyleDef? = styles[id] ?: defaultStyleId?.let { styles[it] }
}

internal object TableStyles {

    const val DEFAULT_STYLE_ID = "{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}"

    fun read(root: XNode?, limits: PptxLimits, colors: ColorModel): TableStyleSheet {
        val list = root ?: return builtIn(colors, limits)
        val def = list.attribute("def")
        val styles = LinkedHashMap<String, TableStyleDef>()
        for (style in list.childrenOf(OoxmlNamespace.A, "tblStyle")) {
            val id = style.attribute("styleId") ?: continue
            styles[id] = parseStyle(style, limits, colors)
        }
        if (DEFAULT_STYLE_ID !in styles) styles[DEFAULT_STYLE_ID] = defaultDef(colors, limits)
        return TableStyleSheet(def, styles)
    }

    fun apply(scene: io.github.pixelrick420.papyrus.viewer.pptx.Scene, sheet: TableStyleSheet) {
        applyNodes(scene.nodes, sheet)
    }

    private fun applyNodes(nodes: List<io.github.pixelrick420.papyrus.viewer.pptx.SceneNode>, sheet: TableStyleSheet) {
        for (node in nodes) {
            when (node) {
                is io.github.pixelrick420.papyrus.viewer.pptx.SceneGraphicFrame -> node.table?.let { apply(it, sheet) }
                is io.github.pixelrick420.papyrus.viewer.pptx.SceneGroup -> applyNodes(node.children, sheet)
                else -> Unit
            }
        }
    }

    fun apply(table: PptxTable, sheet: TableStyleSheet) {
        val def = sheet.styleOf(table.styleId) ?: return
        val rows = table.rows.size
        val cols = table.grid.columnWidths.size
        for (r in table.rows.indices) {
            val row = table.rows[r]
            for (c in row.cells.indices) {
                val cell = row.cells[c]
                if (cell.horizontalMerge || cell.verticalMerge) continue
                val part = resolvePart(def, table.properties, r, c, rows, cols)
                cell.properties = mergeCell(cell.properties, part)
            }
        }
    }

    private fun resolvePart(
        def: TableStyleDef,
        flags: TableStyleFlags,
        row: Int,
        col: Int,
        rowCount: Int,
        colCount: Int,
    ): TableStylePart {
        val order = ArrayList<String>(8)
        order += "wholeTbl"
        if (flags.bandRow) order += if (row % 2 == 0) "band1H" else "band2H"
        if (flags.bandCol) order += if (col % 2 == 0) "band1V" else "band2V"
        val firstRow = flags.firstRow && row == 0
        val lastRow = flags.lastRow && row == rowCount - 1
        val firstCol = flags.firstCol && col == 0
        val lastCol = flags.lastCol && col == colCount - 1
        if (firstRow) order += "firstRow"
        if (lastRow) order += "lastRow"
        if (firstCol) order += "firstCol"
        if (lastCol) order += "lastCol"
        if (firstRow && firstCol) order += "nwCell"
        if (firstRow && lastCol) order += "neCell"
        if (lastRow && firstCol) order += "swCell"
        if (lastRow && lastCol) order += "seCell"
        var fill = def.parts["wholeTbl"]?.fill
        var borders = def.parts["wholeTbl"]?.borders ?: TableBorders()
        var textColor = def.parts["wholeTbl"]?.textColor
        var bold = def.parts["wholeTbl"]?.bold
        for (name in order) {
            val part = def.parts[name] ?: continue
            if (part.fill != null) fill = part.fill
            borders = mergeBorders(borders, part.borders)
            if (part.textColor != null) textColor = part.textColor
            if (part.bold != null) bold = part.bold
        }
        return TableStylePart(fill, borders, textColor, bold)
    }

    private fun mergeCell(base: TableCellProperties, style: TableStylePart): TableCellProperties = TableCellProperties(
        margins = base.margins,
        anchor = base.anchor,
        borders = mergeBorders(style.borders, base.borders),
        fill = base.fill ?: style.fill,
    )

    private fun mergeBorders(lower: TableBorders, higher: TableBorders): TableBorders = TableBorders(
        left = higher.left ?: lower.left,
        right = higher.right ?: lower.right,
        top = higher.top ?: lower.top,
        bottom = higher.bottom ?: lower.bottom,
        topLeftToBottomRight = higher.topLeftToBottomRight ?: lower.topLeftToBottomRight,
        bottomLeftToTopRight = higher.bottomLeftToTopRight ?: lower.bottomLeftToTopRight,
    )

    private fun parseStyle(style: XNode, limits: PptxLimits, colors: ColorModel): TableStyleDef {
        val parts = LinkedHashMap<String, TableStylePart>()
        for (child in style.children) {
            if (child.name.namespace != OoxmlNamespace.A) continue
            parts[child.name.localName] = parsePart(child, limits, colors)
        }
        return TableStyleDef(parts)
    }

    private fun parsePart(part: XNode, limits: PptxLimits, colors: ColorModel): TableStylePart {
        val tcStyle = part.child(OoxmlNamespace.A, "tcStyle")
        val txStyle = part.child(OoxmlNamespace.A, "tcTxStyle")
        return TableStylePart(
            fill = tcStyle?.let { parseFill(it, limits, colors) },
            borders = tcStyle?.child(OoxmlNamespace.A, "tcBdr")?.let { parseBorders(it, limits, colors) } ?: TableBorders(),
            textColor = txStyle?.let { textColorOf(it, colors) },
            bold = txStyle?.attribute("b")?.let { it == "on" || it == "1" || it.equals("true", true) },
        )
    }

    private fun parseBorders(tcBdr: XNode, limits: PptxLimits, colors: ColorModel): TableBorders = TableBorders(
        left = parseBorder(tcBdr.child(OoxmlNamespace.A, "left") ?: tcBdr.child(OoxmlNamespace.A, "lnL"), limits, colors),
        right = parseBorder(tcBdr.child(OoxmlNamespace.A, "right") ?: tcBdr.child(OoxmlNamespace.A, "lnR"), limits, colors),
        top = parseBorder(tcBdr.child(OoxmlNamespace.A, "top") ?: tcBdr.child(OoxmlNamespace.A, "lnT"), limits, colors),
        bottom = parseBorder(tcBdr.child(OoxmlNamespace.A, "bottom") ?: tcBdr.child(OoxmlNamespace.A, "lnB"), limits, colors),
        topLeftToBottomRight = parseBorder(tcBdr.child(OoxmlNamespace.A, "tl2br") ?: tcBdr.child(OoxmlNamespace.A, "lnTlToBr"), limits, colors),
        bottomLeftToTopRight = parseBorder(tcBdr.child(OoxmlNamespace.A, "tr2bl") ?: tcBdr.child(OoxmlNamespace.A, "lnBlToTr"), limits, colors),
    )

    private fun parseBorder(node: XNode?, limits: PptxLimits, colors: ColorModel): TableBorder? {
        val line = node?.child(OoxmlNamespace.A, "ln") ?: node ?: return null
        if (line.child(OoxmlNamespace.A, "noFill") != null) return TableBorder(0.0, ScenePaint.None)
        val width = line.attribute("w")?.toDoubleOrNull() ?: 12700.0
        return TableBorder(width, parseFill(line, limits, colors))
    }

    private fun parseFill(container: XNode, limits: PptxLimits, colors: ColorModel): ScenePaint? {
        val fill = container.children.firstOrNull {
            it.name.namespace == OoxmlNamespace.A && it.name.localName in FILL_TAGS
        } ?: return null
        return Fills.paintOf(fill, null, colors, limits.maxGradientStops.coerceAtLeast(0))
    }

    private fun textColorOf(txStyle: XNode, colors: ColorModel): io.github.pixelrick420.papyrus.viewer.pptx.Rgba? {
        val fontRef = txStyle.child(OoxmlNamespace.A, "fontRef")
        val colorNode = fontRef?.children?.firstOrNull { it.name.namespace == OoxmlNamespace.A }
            ?: txStyle.children.firstOrNull { it.name.localName in COLOR_NAMES }
        return colorNode?.let { colors.resolve(it, null) }
    }

    private fun builtIn(colors: ColorModel, limits: PptxLimits): TableStyleSheet =
        TableStyleSheet(DEFAULT_STYLE_ID, mapOf(DEFAULT_STYLE_ID to defaultDef(colors, limits)))

    private fun defaultDef(colors: ColorModel, limits: PptxLimits): TableStyleDef = TableStyleDef(emptyMap())

    private val FILL_TAGS = setOf("noFill", "solidFill", "gradFill", "blipFill", "pattFill", "grpFill")
    private val COLOR_NAMES = setOf("srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr", "hslClr")
}
