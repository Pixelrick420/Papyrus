package io.github.pixelrick420.papyrus.viewer.pptx.table

import io.github.pixelrick420.papyrus.viewer.pptx.ScenePaint
import io.github.pixelrick420.papyrus.viewer.pptx.text.BlockMeasurer
import io.github.pixelrick420.papyrus.viewer.pptx.text.LineBreaker
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextBody
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextInsets
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextLayoutEngine
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextParagraph
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextParagraphProperties
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextRun
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextRunProperties
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextSpacing
import io.github.pixelrick420.papyrus.viewer.pptx.text.TextStyleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TableLayoutTest {

    @Test
    fun `rows auto-grow to fit cell text`() {
        val textBody = TextBody(
            paragraphs = listOf(
                TextParagraph(
                    runs = listOf(TextRun.Text("abcdefghij", TextRunProperties(sizePt = 18.0))),
                    endProperties = TextRunProperties(sizePt = 18.0),
                ),
            ),
        )
        val table = PptxTable(
            grid = TableGrid(listOf(50_000.0)),
            rows = listOf(TableRow(10_000.0, listOf(TableCell(textBody = null).apply { resolvedText = textBody }))),
        )
        val engine = TextLayoutEngine(BlockMeasurer(charPt = 10_000.0, spacePt = 10_000.0), breaker = simpleBreaker())
        layoutDirect(table, engine)

        val layout = table.layout!!
        assertTrue(layout.totalHeightEmu > 10_000.0)
        assertEquals(layout.rowHeightsEmu.single(), layout.totalHeightEmu, 0.001)
        assertTrue(table.rows.single().drawnHeightEmu > 10_000.0)
    }

    @Test
    fun `shared borders resolve to one line per edge`() {
        val border = TableBorder(12700.0, ScenePaint.Solid(io.github.pixelrick420.papyrus.viewer.pptx.Rgba.BLACK))
        val table = PptxTable(
            grid = TableGrid(listOf(1000.0, 1000.0)),
            rows = listOf(
                TableRow(500.0, listOf(cellWithRight(border), cellWithLeft(TableBorder(6350.0, ScenePaint.Solid(io.github.pixelrick420.papyrus.viewer.pptx.Rgba.WHITE))))),
            ),
        )
        val engine = TextLayoutEngine(BlockMeasurer(), breaker = simpleBreaker())
        layoutDirect(table, engine)

        val verticals = table.layout!!.resolvedBorders.filter { it.x1 == it.x2 }
        val middle = verticals.filter { kotlin.math.abs(it.x1 - 1000.0) < 0.001 }
        assertEquals(1, middle.size)
        assertEquals(12700.0, middle.single().border.widthEmu, 0.001)
    }

    @Test
    fun `cell text keeps only one set of insets`() {
        val cell = TableCell(textBody = null).apply {
            resolvedText = TextBody(paragraphs = listOf(TextParagraph()))
        }
        val table = PptxTable(
            grid = TableGrid(listOf(500_000.0)),
            rows = listOf(TableRow(100_000.0, listOf(cell))),
        )
        val engine = TextLayoutEngine(BlockMeasurer(), breaker = simpleBreaker())
        layoutDirect(table, engine)

        assertEquals(TextInsets.ZERO, cell.resolvedText!!.properties.insets)
        val origin = TableLayout.cellTextOrigin(
            cell,
            cell.resolvedText!!,
            table.layout!!.cellFrames.values.single(),
            cell.textLayout!!,
        )
        assertEquals(TableInsets().left, origin.x, 0.001)
        assertEquals(TableInsets().top, origin.y, 0.001)
    }

    @Test
    fun `first paragraph space before is ignored in a cell`() {
        fun layoutWith(spaceBefore: TextSpacing?): Double {
            val body = TextBody(
                paragraphs = listOf(
                    TextParagraph(
                        runs = listOf(TextRun.Text("x", TextRunProperties(sizePt = 12.0))),
                        endProperties = TextRunProperties(sizePt = 12.0),
                        properties = TextParagraphProperties(
                            spaceBefore = spaceBefore,
                        ),
                    ),
                ),
            )
            val table = PptxTable(
                grid = TableGrid(listOf(500_000.0)),
                rows = listOf(TableRow(100_000.0, listOf(TableCell(textBody = null).apply { resolvedText = body }))),
            )
            layoutDirect(table, TextLayoutEngine(BlockMeasurer(), breaker = simpleBreaker()))
            return table.rows.single().cells.single().textLayout!!.heightPt
        }

        assertEquals(
            layoutWith(null),
            layoutWith(TextSpacing(points = 12.0)),
            0.001,
        )
    }

    private fun layoutDirect(table: PptxTable, engine: TextLayoutEngine) {
        val scene = io.github.pixelrick420.papyrus.viewer.pptx.Scene(
            io.github.pixelrick420.papyrus.viewer.pptx.SceneSize(10000.0, 10000.0),
            listOf(
                io.github.pixelrick420.papyrus.viewer.pptx.SceneGraphicFrame(
                    id = 1,
                    name = "t",
                    frame = io.github.pixelrick420.papyrus.viewer.pptx.SceneRect(0.0, 0.0, 2000.0, 500.0),
                    kind = io.github.pixelrick420.papyrus.viewer.pptx.GraphicKind.TABLE,
                    table = table,
                ),
            ),
        )
        TableLayout.layout(scene, TextStyleResolver(), engine)
    }

    private fun cellWithRight(border: TableBorder): TableCell = TableCell(
        textBody = null,
        properties = TableCellProperties(borders = TableBorders(right = border)),
    )

    private fun cellWithLeft(border: TableBorder): TableCell = TableCell(
        textBody = null,
        properties = TableCellProperties(borders = TableBorders(left = border)),
    )

    private fun simpleBreaker(): LineBreaker = LineBreaker { text, _ ->
        val breaks = ArrayList<Int>()
        for (i in 1 until text.length) {
            if (text[i - 1].isWhitespace() || !text[i].isLetterOrDigit()) breaks += i
        }
        breaks
    }
}
