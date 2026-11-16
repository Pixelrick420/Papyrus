package io.github.pixelrick420.papyrus.viewer.pptx.table

import io.github.pixelrick420.papyrus.viewer.pptx.Scene
import io.github.pixelrick420.papyrus.viewer.pptx.SceneGraphicFrame
import io.github.pixelrick420.papyrus.viewer.pptx.SceneGroup
import io.github.pixelrick420.papyrus.viewer.pptx.SceneNode
import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import io.github.pixelrick420.papyrus.viewer.pptx.SceneRect
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextBody
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextLayoutEngine
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextStyleResolver
import kotlin.math.max

internal object TableLayout {

    private const val EMU_PER_POINT = 12_700.0

    fun layout(scene: Scene, resolver: TextStyleResolver, engine: TextLayoutEngine) {
        layoutNodes(scene.nodes, resolver, engine)
    }

    private fun layoutNodes(nodes: List<SceneNode>, resolver: TextStyleResolver, engine: TextLayoutEngine) {
        for (node in nodes) {
            when (node) {
                is SceneGraphicFrame -> node.table?.let { layoutTable(it, resolver, engine) }
                is SceneGroup -> layoutNodes(node.children, resolver, engine)
                else -> Unit
            }
        }
    }

    private fun layoutTable(
        table: PptxTable,
        resolver: TextStyleResolver,
        engine: TextLayoutEngine,
    ) {
        val colWidths = table.grid.columnWidths
        val rowCount = table.rows.size
        val colCount = colWidths.size
        if (rowCount == 0 || colCount == 0) return
        val rowHeights = DoubleArray(rowCount) { table.rows[it].heightEmu }
        val colOffsets = offsetsOf(colWidths)
        val owners = ownersOf(table)

        for (r in 0 until rowCount) {
            val row = table.rows[r]
            for (c in 0 until minOf(row.cells.size, colCount)) {
                val cell = row.cells[c]
                if (cell.horizontalMerge || cell.verticalMerge) continue
                val body = cell.textBody?.let { resolver.resolve(it) } ?: cell.resolvedText ?: TextBody()
                cell.resolvedText = body
                val spanCols = cell.gridSpan.coerceIn(1, colCount - c)
                val spanRows = cell.rowSpan.coerceIn(1, rowCount - r)
                val cellWidth = colOffsets[c + spanCols] - colOffsets[c]
                val insets = cell.properties.margins
                val contentWidth = (cellWidth - insets.left - insets.right).coerceAtLeast(0.0)
                val layout = engine.layout(body, contentWidth)
                cell.textLayout = layout
                val contentHeight = layout.heightPt * EMU_PER_POINT + insets.top + insets.bottom
                if (spanRows == 1) {
                    rowHeights[r] = max(rowHeights[r], contentHeight)
                } else {
                    distributeSpanHeight(rowHeights, r, spanRows, contentHeight)
                }
            }
        }

        val rowOffsets = offsetsOf(rowHeights.toList())
        for (r in 0 until rowCount) table.rows[r].drawnHeightEmu = rowHeights[r]
        val cellFrames = LinkedHashMap<Pair<Int, Int>, SceneRect>()
        for (r in 0 until rowCount) {
            val row = table.rows[r]
            for (c in 0 until minOf(row.cells.size, colCount)) {
                val cell = row.cells[c]
                if (cell.horizontalMerge || cell.verticalMerge) continue
                val spanCols = cell.gridSpan.coerceIn(1, colCount - c)
                val spanRows = cell.rowSpan.coerceIn(1, rowCount - r)
                cellFrames[r to c] = SceneRect(
                    colOffsets[c],
                    rowOffsets[r],
                    colOffsets[c + spanCols] - colOffsets[c],
                    rowOffsets[r + spanRows] - rowOffsets[r],
                )
            }
        }
        table.layout = TableLayoutResult(
            rowHeightsEmu = rowHeights.toList(),
            totalHeightEmu = rowOffsets.last(),
            cellFrames = cellFrames,
            resolvedBorders = resolveBorders(table, owners, colOffsets, rowOffsets),
        )
    }

    private fun offsetsOf(values: List<Double>): DoubleArray {
        val offsets = DoubleArray(values.size + 1)
        for (i in values.indices) offsets[i + 1] = offsets[i] + values[i]
        return offsets
    }

    private fun distributeSpanHeight(rowHeights: DoubleArray, start: Int, span: Int, needed: Double) {
        val current = (start until start + span).sumOf { rowHeights[it] }
        if (needed <= current) return
        val extra = needed - current
        val per = extra / span
        for (i in start until start + span) rowHeights[i] += per
    }

    private fun ownersOf(table: PptxTable): Array<IntArray> {
        val rows = table.rows.size
        val cols = table.grid.columnWidths.size
        val owners = Array(rows) { IntArray(cols) { -1 } }
        for (r in 0 until rows) {
            val cells = table.rows[r].cells
            for (c in 0 until minOf(cells.size, cols)) {
                val cell = cells[c]
                if (cell.horizontalMerge || cell.verticalMerge) continue
                val rs = cell.rowSpan.coerceIn(1, rows - r)
                val cs = cell.gridSpan.coerceIn(1, cols - c)
                for (rr in r until r + rs) {
                    for (cc in c until c + cs) owners[rr][cc] = r * cols + c
                }
            }
        }
        return owners
    }

    private fun resolveBorders(
        table: PptxTable,
        owners: Array<IntArray>,
        colOffsets: DoubleArray,
        rowOffsets: DoubleArray,
    ): List<ResolvedTableBorder> {
        val rows = table.rows.size
        val cols = table.grid.columnWidths.size
        val out = ArrayList<ResolvedTableBorder>()
        for (r in 0..rows) {
            for (c in 0 until cols) {
                val above = if (r > 0) owners[r - 1][c] else -1
                val below = if (r < rows) owners[r][c] else -1
                val border = (if (r == 0) {
                    bottomBorderOf(cellAt(table, below, cols))
                } else if (r == rows) {
                    topBorderOf(cellAt(table, above, cols))
                } else {
                    choose(topBorderOf(cellAt(table, below, cols)), bottomBorderOf(cellAt(table, above, cols)))
                }) ?: continue
                if (isNone(border)) continue
                out += ResolvedTableBorder(colOffsets[c], rowOffsets[r], colOffsets[c + 1], rowOffsets[r], border)
            }
        }
        for (r in 0 until rows) {
            for (c in 0..cols) {
                val left = if (c > 0) owners[r][c - 1] else -1
                val right = if (c < cols) owners[r][c] else -1
                val border = (if (c == 0) {
                    rightBorderOf(cellAt(table, right, cols))
                } else if (c == cols) {
                    leftBorderOf(cellAt(table, left, cols))
                } else {
                    choose(leftBorderOf(cellAt(table, right, cols)), rightBorderOf(cellAt(table, left, cols)))
                }) ?: continue
                if (isNone(border)) continue
                out += ResolvedTableBorder(colOffsets[c], rowOffsets[r], colOffsets[c], rowOffsets[r + 1], border)
            }
        }
        return out
    }

    private fun cellAt(table: PptxTable, owner: Int, cols: Int): TableCell? {
        if (owner < 0 || cols == 0) return null
        val r = owner / cols
        val c = owner % cols
        return table.rows.getOrNull(r)?.cells?.getOrNull(c)
    }

    private fun topBorderOf(cell: TableCell?): TableBorder? = cell?.properties?.borders?.top
    private fun bottomBorderOf(cell: TableCell?): TableBorder? = cell?.properties?.borders?.bottom
    private fun leftBorderOf(cell: TableCell?): TableBorder? = cell?.properties?.borders?.left
    private fun rightBorderOf(cell: TableCell?): TableBorder? = cell?.properties?.borders?.right

    private fun choose(a: TableBorder?, b: TableBorder?): TableBorder? {
        if (a == null) return b
        if (b == null) return a
        if (isNone(a) && !isNone(b)) return b
        if (isNone(b) && !isNone(a)) return a
        return if (b.widthEmu >= a.widthEmu) b else a
    }

    private fun isNone(border: TableBorder): Boolean = border.paint == ScenePaint.None || border.widthEmu <= 0.0
}
