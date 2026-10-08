package com.papyrus.app.ui.viewer

import com.papyrus.app.viewer.OfficeCell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Table geometry: a serial number must not get a prose column's width, a table must fit a phone when it can,
 * and every cell must be as tall as its row.
 */
class TableSizingTest {

    private val em = 12f
    private val chrome = 18f
    private val prose = "The committee reviewed the proposal at length and asked for revisions to the budget, " +
        "the timeline and the staffing plan before it could be approved for the next quarter."

    private fun measure(rows: List<List<OfficeCell>>, columns: Int = rows.maxOf { r -> r.maxOf { it.lastColumn } + 1 }) =
        measureColumns(rows, columns, em, chrome)

    private fun row(vararg text: String) = text.mapIndexed { i, t -> OfficeCell(t, column = i) }

    @Test
    fun `a serial number column is much narrower than a prose column`() {
        val metrics = measure(listOf(row("1", prose, "x"), row("2", prose, "y")))

        assertTrue(metrics.preferredDp[0] < metrics.preferredDp[1] / 3)
    }

    @Test
    fun `columns are not all the same width`() {
        val metrics = measure(listOf(row("1", prose, "12/03/2024")))

        assertTrue(metrics.preferredDp.toSet().size == 3)
    }

    @Test
    fun `an empty or one-character column still gets a minimum width`() {
        val metrics = measure(listOf(row("", "1"), row("", "2")))

        val floor = 3 * em + chrome
        assertEquals(floor, metrics.preferredDp[0], 0.01f)
        assertTrue(metrics.preferredDp[1] >= floor)
        assertTrue(metrics.minDp[0] >= floor)
    }

    @Test
    fun `prose is capped so a long paragraph does not make an enormous column`() {
        val metrics = measure(listOf(row("1", prose.repeat(20))))

        assertEquals(22 * em + chrome, metrics.preferredDp[1], 0.01f)
    }

    @Test
    fun `a column is sized by its widest cell, not its first`() {
        val metrics = measure(listOf(row("a"), row("a considerably longer entry"), row("b")))

        assertTrue(metrics.preferredDp[0] > 100f)
    }

    @Test
    fun `lines of a multi-paragraph cell are measured separately`() {
        val joined = measure(listOf(row("short\nshort")))
        val single = measure(listOf(row("short")))

        assertEquals(single.preferredDp[0], joined.preferredDp[0], 0.01f)
    }

    @Test
    fun `a column with prose can be squeezed but one with a short value cannot`() {
        val metrics = measure(listOf(row("12/03/2024", prose)))

        assertEquals(metrics.preferredDp[0], metrics.minDp[0], 0.01f)
        assertTrue(metrics.minDp[1] < metrics.preferredDp[1])
    }

    @Test
    fun `the squeezed width still holds the longest word`() {
        val word = "Internationalization"
        val metrics = measure(listOf(row("$word is a long word then some more ordinary text to make it wrap", "x")))

        // 20 characters is wider than the 8em squeeze floor, so the word sets the minimum.
        assertTrue(metrics.minDp[0] > 8 * em + chrome)
    }

    @Test
    fun `a very long unbreakable string does not set the minimum`() {
        val url = "https://example.com/" + "a".repeat(200)
        val metrics = measure(listOf(row(url, "x")))

        assertTrue(metrics.minDp[0] <= 12 * em * 1.1f + chrome)
    }

    @Test
    fun `wide characters count as a full em each`() {
        val metrics = measure(listOf(row("\u4e2d\u6587\u6587\u6863\u8868\u683c")))

        assertTrue(metrics.preferredDp[0] >= 6 * em + chrome)
    }

    @Test
    fun `widths follow the text size so zoom scales the columns`() {
        val rows = listOf(row("1", prose))
        val normal = measureColumns(rows, 2, 12f, chrome)
        val zoomed = measureColumns(rows, 2, 24f, chrome)

        assertTrue(zoomed.preferredDp[1] > normal.preferredDp[1])
        assertTrue(zoomed.preferredDp[0] > normal.preferredDp[0])
    }

    @Test
    fun `a long merged title does not inflate the columns it spans`() {
        val title = OfficeCell(prose.repeat(10), column = 0, colspan = 3)
        val metrics = measure(listOf(listOf(title), row("1", "2", "3")))

        // Capped like prose: all three columns together stay within one prose column's worth.
        assertTrue(metrics.preferredDp.sum() <= 22 * em + chrome + 0.01f)
    }

    @Test
    fun `a merged cell widens the columns it spans only as far as it needs`() {
        val header = OfficeCell("Contact details here", column = 0, colspan = 2)
        val metrics = measure(listOf(listOf(header), row("a", "b")))

        val single = measure(listOf(row("a", "b")))
        assertTrue(metrics.preferredDp.sum() > single.preferredDp.sum())
        assertEquals(metrics.preferredDp[0], metrics.preferredDp[1], 0.01f)
    }

    @Test
    fun `a merged cell that already fits leaves widths alone`() {
        val header = OfficeCell("Hi", column = 0, colspan = 2)
        val metrics = measure(listOf(listOf(header), row(prose, prose)))

        assertEquals(22 * em + chrome, metrics.preferredDp[0], 0.01f)
        assertEquals(22 * em + chrome, metrics.preferredDp[1], 0.01f)
    }

    @Test
    fun `a table that fits is not stretched`() {
        val metrics = measure(listOf(row("1", "Alice")))

        val fitted = fitColumns(metrics, availableDp = 1000f)

        assertEquals(metrics.preferredDp.toList(), fitted.toList())
    }

    @Test
    fun `an unbounded width leaves the preferred widths`() {
        val metrics = measure(listOf(row("1", prose)))

        assertEquals(metrics.preferredDp.toList(), fitColumns(metrics, Float.POSITIVE_INFINITY).toList())
    }

    @Test
    fun `a table too wide for the screen shrinks its prose column and spares the short ones`() {
        val metrics = measure(listOf(row("1", prose, "12/03/2024")))
        val available = metrics.preferredDp.sum() - 60f

        val fitted = fitColumns(metrics, available)

        assertEquals(available, fitted.sum(), 0.1f)
        assertEquals(metrics.preferredDp[0], fitted[0], 0.01f)
        assertEquals(metrics.preferredDp[2], fitted[2], 0.01f)
        assertTrue(fitted[1] < metrics.preferredDp[1])
    }

    @Test
    fun `fitting never goes below the minimum`() {
        val metrics = measure(listOf(row("1", prose, prose)))
        val available = metrics.minDp.sum() + 5f

        val fitted = fitColumns(metrics, available)

        fitted.forEachIndexed { i, w -> assertTrue(w >= metrics.minDp[i] - 0.01f) }
        assertEquals(available, fitted.sum(), 0.1f)
    }

    @Test
    fun `when even the minimums do not fit the table keeps them and scrolls`() {
        val metrics = measure(listOf(row(prose, prose, prose, prose, prose, prose)))

        val fitted = fitColumns(metrics, availableDp = 100f)

        assertEquals(metrics.minDp.toList(), fitted.toList())
    }

    @Test
    fun `fitting does not modify the metrics`() {
        val metrics = measure(listOf(row("1", prose)))
        val before = metrics.preferredDp.toList()

        fitColumns(metrics, 150f)

        assertEquals(before, metrics.preferredDp.toList())
    }

    @Test
    fun `cells outside the declared columns are ignored rather than crashing`() {
        val rows = listOf(listOf(OfficeCell("a", column = 0), OfficeCell("stray", column = 5)))

        val metrics = measureColumns(rows, columnCount = 2, emDp = em, chromeDp = chrome)

        assertEquals(2, metrics.preferredDp.size)
    }

    // --- row heights ---

    private fun cell(row: Int, column: Int, rowspan: Int = 1, colspan: Int = 1) = GridCell(row, column, colspan, rowspan)

    @Test
    fun `a row is as tall as its tallest cell, so a short cell is stretched to match`() {
        val cells = listOf(cell(0, 0), cell(0, 1), cell(1, 0), cell(1, 1))

        val heights = rowHeights(cells, intArrayOf(50, 300, 50, 50), rowCount = 2, minRow = 40)

        assertEquals(listOf(300, 50), heights.toList())
    }

    @Test
    fun `a row never drops under the minimum height`() {
        val cells = listOf(cell(0, 0), cell(1, 0))

        val heights = rowHeights(cells, intArrayOf(10, 0), rowCount = 2, minRow = 40)

        assertEquals(listOf(40, 40), heights.toList())
    }

    @Test
    fun `a row with no cell at all still gets the minimum`() {
        val heights = rowHeights(listOf(cell(0, 0)), intArrayOf(90), rowCount = 3, minRow = 40)

        assertEquals(listOf(90, 40, 40), heights.toList())
    }

    @Test
    fun `a rowspan cell that fits inside its rows does not change them`() {
        val cells = listOf(cell(0, 0, rowspan = 2), cell(0, 1), cell(1, 1))

        val heights = rowHeights(cells, intArrayOf(60, 50, 50), rowCount = 2, minRow = 40)

        assertEquals(listOf(50, 50), heights.toList())
    }

    @Test
    fun `a rowspan cell taller than its rows grows only the last of them`() {
        val cells = listOf(cell(0, 0, rowspan = 3), cell(0, 1), cell(1, 1), cell(2, 1))

        val heights = rowHeights(cells, intArrayOf(400, 50, 50, 50), rowCount = 3, minRow = 40)

        assertEquals(listOf(50, 50, 300), heights.toList())
        assertEquals(400, heights.sum())
    }

    @Test
    fun `a rowspan running past the last row is cut at it`() {
        val cells = listOf(cell(0, 0), cell(1, 0, rowspan = 5))

        val heights = rowHeights(cells, intArrayOf(50, 200), rowCount = 2, minRow = 40)

        assertEquals(listOf(50, 200), heights.toList())
    }

    @Test
    fun `a rowspan sees rows already grown by their own cells`() {
        val cells = listOf(cell(0, 0, rowspan = 2), cell(0, 1), cell(1, 1))

        val heights = rowHeights(cells, intArrayOf(150, 100, 100), rowCount = 2, minRow = 40)

        assertEquals(listOf(100, 100), heights.toList())
    }
}
