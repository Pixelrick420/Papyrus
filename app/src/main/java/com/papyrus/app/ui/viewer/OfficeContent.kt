package com.papyrus.app.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papyrus.app.R
import com.papyrus.app.viewer.OfficeBlock
import com.papyrus.app.viewer.OfficeCell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/** EMF and WMF have no Android decoder, so a bad image shows a placeholder, not a dead document. */
@Composable
internal fun OfficeBlockView(
    block: OfficeBlock,
    modifier: Modifier = Modifier,
    highlight: FindHighlight? = null,
    reveal: RevealTicket? = null,
) {
    when (block) {
        is OfficeBlock.Heading -> FindText(
            text = block.text,
            highlight = highlight,
            style = headingStyle(block.level),
            modifier = modifier.padding(top = 16.dp, bottom = 4.dp),
            reveal = reveal,
        )
        is OfficeBlock.Paragraph -> FindText(
            text = block.text,
            highlight = highlight,
            style = MaterialTheme.typography.bodyLarge,
            modifier = modifier.padding(vertical = 4.dp),
            reveal = reveal,
        )
        is OfficeBlock.Table -> OfficeTableView(block, modifier.padding(vertical = 8.dp), highlight, reveal)
        is OfficeBlock.Image -> OfficeImageView(block, modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun headingStyle(level: Int) = when (level.coerceIn(1, 6)) {
    1 -> MaterialTheme.typography.headlineMedium
    2 -> MaterialTheme.typography.headlineSmall
    3 -> MaterialTheme.typography.titleLarge
    4 -> MaterialTheme.typography.titleMedium
    5 -> MaterialTheme.typography.titleSmall
    else -> MaterialTheme.typography.bodyLarge
}

/**
 * Column widths come from the cells' own content ([measureColumns], [fitColumns]) and every cell is
 * drawn as tall as its row, so a serial-number column stays narrow beside a prose one and a short cell's
 * border reaches the bottom of its row. A cell that spans rows covers all of them, which needs a layout
 * that sees every row at once: [TableGrid].
 */
@Composable
private fun OfficeTableView(
    table: OfficeBlock.Table,
    modifier: Modifier = Modifier,
    highlight: FindHighlight? = null,
    reveal: RevealTicket? = null,
) {
    val columnCount = remember(table) { table.rows.maxOfOrNull { row -> row.maxOf { it.lastColumn } + 1 } ?: 0 }
    if (columnCount == 0) return
    val hScroll = rememberScrollState()
    // The table's current match is numbered across the whole table, in reading order. Each cell needs
    // to know how many matches came before it to tell which, if any, is its own.
    val matchesBefore = remember(table, highlight?.query) {
        val query = highlight?.query
        var seen = 0
        table.rows.map { row ->
            row.map { cell ->
                seen.also { if (query != null) seen += countOccurrences(cell, query) }
            }
        }
    }
    // (row, column) pairs an earlier rowspan owns. The extractor omits those, so re-derive them.
    val covered = remember(table) {
        buildSet {
            table.rows.forEachIndexed { rowIndex, row ->
                row.filter { it.rowspan > 1 }.forEach { cell ->
                    for (coveredRow in rowIndex + 1 until rowIndex + cell.rowspan) {
                        for (column in cell.column..cell.lastColumn) add(coveredRow to column)
                    }
                }
            }
        }
    }

    val cellStyle = MaterialTheme.typography.bodySmall
    // Zoom scales fontScale, so measuring in dp *at the current scale* is what makes columns follow it.
    val emDp = with(LocalDensity.current) { (cellStyle.fontSize.takeIf { it.isSp } ?: 12.sp).toDp().value }
    val metrics = remember(table, columnCount, emDp) {
        measureColumns(table.rows, columnCount, emDp, CELL_CHROME.value)
    }

    // The viewport width is what a table that is too wide has to fit, so it is read here rather than
    // inside the scroller, which is offered unbounded width.
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columnWidths = remember(metrics, maxWidth) { fitColumns(metrics, maxWidth.value) }
        Box(Modifier.horizontalScroll(hScroll)) {
            TableGrid(columnWidthsDp = columnWidths, rowCount = table.rows.size) {
                table.rows.forEachIndexed { rowIndex, row ->
                    row.forEachIndexed { cellIndex, cell ->
                        CellBox(
                            cell = cell,
                            style = cellStyle,
                            modifier = Modifier.gridCell(rowIndex, cell.column, cell.colspan, cell.rowspan),
                            highlight = highlight?.skipping(matchesBefore[rowIndex][cellIndex]),
                            reveal = reveal,
                        )
                    }
                    // A column no cell owns and no rowspan covers is a real gap in the source. It still
                    // gets a box, or a short row would end in blank space instead of an empty cell. A
                    // covered column gets none, so a merge has no internal border.
                    repeat(columnCount) { column ->
                        val owned = row.any { column in it.column..it.lastColumn }
                        if (!owned && (rowIndex to column) !in covered) {
                            CellBox(
                                cell = null,
                                style = cellStyle,
                                modifier = Modifier.gridCell(rowIndex, column),
                                highlight = null,
                                reveal = null,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val CELL_PADDING = 8.dp
private val CELL_GAP = 1.dp
private val MIN_CELL_HEIGHT = 40.dp

/** Width a cell spends outside its text: gap and padding, both sides. */
private val CELL_CHROME = (CELL_GAP + CELL_PADDING) * 2

/** `Constraints` cannot represent a height past this; a cell taller than it is cut short rather than crashing. */
private const val MAX_CELL_PX = 250_000

/** Marks which cells of the [TableGrid] a child occupies; [TableGrid] reads it back off the child. */
private fun Modifier.gridCell(row: Int, column: Int, colspan: Int = 1, rowspan: Int = 1): Modifier =
    layoutId(GridCell(row, column, colspan.coerceAtLeast(1), rowspan.coerceAtLeast(1)))

/**
 * Lays its children out on a grid of [columnWidthsDp]; each child says which cells it occupies with
 * [gridCell]. A row is as tall as the tallest cell sitting in that row alone, and never under
 * [MIN_CELL_HEIGHT]. A cell spanning rows takes the height of all of them, and grows the last if it is
 * taller than they are together.
 *
 * Heights come from intrinsics, then each child is measured once at exactly the size it will be drawn:
 * that is what makes a short cell's box fill its row. (A `fillMaxHeight` on a child inside a `Row`
 * cannot, because the row's own height is unbounded here.)
 */
@Composable
private fun TableGrid(
    columnWidthsDp: FloatArray,
    rowCount: Int,
    content: @Composable () -> Unit,
) {
    Layout(content = content) { measurables, _ ->
        val columns = columnWidthsDp.size
        val columnX = IntArray(columns + 1)
        columnWidthsDp.forEachIndexed { i, width -> columnX[i + 1] = columnX[i] + width.dp.roundToPx() }

        val cells = measurables.map { it.layoutId as GridCell }
        val widths = IntArray(cells.size)
        val natural = IntArray(cells.size)
        cells.forEachIndexed { i, cell ->
            widths[i] = columnX[min(cell.column + cell.colspan, columns)] - columnX[cell.column]
            natural[i] = measurables[i].minIntrinsicHeight(widths[i])
        }
        val heights = rowHeights(cells, natural, rowCount, minRow = MIN_CELL_HEIGHT.roundToPx())
        val rowY = IntArray(rowCount + 1)
        for (row in 0 until rowCount) rowY[row + 1] = rowY[row] + heights[row]

        val placeables = measurables.mapIndexed { i, measurable ->
            val cell = cells[i]
            val height = (rowY[min(cell.row + cell.rowspan, rowCount)] - rowY[cell.row]).coerceAtMost(MAX_CELL_PX)
            measurable.measure(Constraints.fixed(widths[i], height))
        }
        layout(columnX[columns], rowY[rowCount]) {
            placeables.forEachIndexed { i, placeable -> placeable.place(columnX[cells[i].column], rowY[cells[i].row]) }
        }
    }
}

/**
 * Takes whatever size [TableGrid] hands it, so the border and fill cover the whole cell, merged or not.
 * Gaps are per cell, so a merged cell shows no internal border.
 */
@Composable
private fun CellBox(
    cell: OfficeCell?,
    style: TextStyle,
    modifier: Modifier,
    highlight: FindHighlight?,
    reveal: RevealTicket?,
) {
    Box(
        modifier
            .padding(CELL_GAP)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
            .padding(CELL_PADDING),
    ) {
        if (cell == null) return@Box
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // The extractor joins a cell's paragraphs with newlines; re-splitting here makes
            // intra-cell spacing independent of the cell's font size. Each line is its own text,
            // so the cell's current match is numbered across them the same way the table's is
            // across its cells.
            var before = 0
            officeCellLines(cell).forEach { line ->
                FindText(
                    text = line,
                    highlight = highlight?.skipping(before),
                    style = style,
                    reveal = reveal,
                )
                if (highlight != null) before += countOccurrences(line, highlight.query)
            }
        }
    }
}

/** `inSampleSize` bounds decode memory; the bounds-only pass gets the source dimensions it needs. */
@Composable
private fun OfficeImageView(block: OfficeBlock.Image, modifier: Modifier = Modifier) {
    val targetPx = viewportWidthPx()
    val state by produceState<ImageState>(ImageState.Loading, block.file.path, targetPx) {
        value = withContext(Dispatchers.IO) { decode(block.file, targetPx) }
    }

    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        when (val current = state) {
            ImageState.Loading -> Text(
                stringResource(R.string.viewer_office_image_loading),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is ImageState.Loaded -> Image(
                // No recycle: Compose may still be drawing this bitmap when the composition leaves.
                bitmap = remember(current.bitmap) { current.bitmap.asImageBitmap() },
                contentDescription = null,
                contentScale = ContentScale.FillWidth,
                modifier = Modifier.fillMaxWidth(),
            )
            ImageState.Failed -> Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    stringResource(R.string.viewer_office_image_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}

private sealed interface ImageState {
    data object Loading : ImageState
    data class Loaded(val bitmap: Bitmap) : ImageState
    data object Failed : ImageState
}

/** From configuration, not a measured constraint: the image is `fillMaxWidth`, so measuring is circular. */
@Composable
private fun viewportWidthPx(): Int = with(LocalDensity.current) {
    LocalConfiguration.current.screenWidthDp.dp.toPx()
}.toInt()

private fun decode(file: File, targetPx: Int): ImageState {
    if (!file.canRead()) return ImageState.Failed
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return ImageState.Failed

    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, targetPx)
    }
    // A null here means the decode failed, not the file missing: an EMF reports valid header
    // bounds but has no Android codec. Both read the same to the user.
    val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return ImageState.Failed
    return ImageState.Loaded(bitmap)
}

private fun sampleSizeFor(sourceWidth: Int, targetPx: Int): Int {
    if (targetPx <= 0) return 1
    var sample = 1
    while (sourceWidth / (sample * 2) >= targetPx) sample *= 2
    return sample
}
