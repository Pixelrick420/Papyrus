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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import com.papyrus.app.viewer.BlockAlign
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
            style = headingStyle(block.level).withAlign(block.align),
            modifier = modifier.padding(top = 16.dp, bottom = 4.dp),
            reveal = reveal,
            spans = block.spans,
        )
        is OfficeBlock.Paragraph -> FindText(
            text = block.text,
            highlight = highlight,
            style = MaterialTheme.typography.bodyLarge.withAlign(block.align),
            modifier = modifier.padding(start = INDENT_STEP * block.indent, top = 4.dp, bottom = 4.dp),
            reveal = reveal,
            spans = block.spans,
        )
        is OfficeBlock.ListItem -> OfficeListItemView(block, modifier, highlight, reveal)
        is OfficeBlock.Note -> OfficeNoteView(block, modifier, highlight, reveal)
        is OfficeBlock.Divider -> HorizontalDivider(modifier.padding(vertical = 12.dp))
        is OfficeBlock.Table -> OfficeTableView(block, modifier.padding(vertical = 8.dp), highlight, reveal)
        is OfficeBlock.Image -> OfficeImageView(block, modifier.padding(vertical = 8.dp))
    }
}

/** One step of paragraph indentation, as extracted: a quarter inch of the page. */
private val INDENT_STEP = 16.dp

/** One level of list nesting, and the room a marker has before the text starts. */
private val LIST_INDENT = 20.dp
private val MARKER_WIDTH = 20.dp

private fun TextStyle.withAlign(align: BlockAlign): TextStyle =
    if (align == BlockAlign.START) this else copy(textAlign = align.toTextAlign())

/** The marker is beside the text rather than in it, so it is neither searchable nor shifted by the find offsets. */
@Composable
private fun OfficeListItemView(
    block: OfficeBlock.ListItem,
    modifier: Modifier,
    highlight: FindHighlight?,
    reveal: RevealTicket?,
) {
    Row(modifier.padding(start = LIST_INDENT * block.level, top = 2.dp, bottom = 2.dp)) {
        Text(
            block.marker,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.widthIn(min = MARKER_WIDTH).padding(end = 8.dp),
        )
        FindText(
            text = block.text,
            highlight = highlight,
            style = MaterialTheme.typography.bodyLarge.withAlign(block.align),
            modifier = Modifier.weight(1f),
            reveal = reveal,
            spans = block.spans,
        )
    }
}

@Composable
private fun OfficeNoteView(
    note: OfficeBlock.Note,
    modifier: Modifier,
    highlight: FindHighlight?,
    reveal: RevealTicket?,
) {
    Row(modifier.padding(vertical = 2.dp)) {
        Text(
            note.label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = MARKER_WIDTH).padding(end = 6.dp),
        )
        FindText(
            text = note.text,
            highlight = highlight,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            reveal = reveal,
            spans = note.spans,
        )
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
 * Column widths come from the cells' own content and every cell is drawn as tall as its row, so a
 * short cell's border reaches the bottom. Row spans need a layout that sees every row: [TableGrid].
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
                    // A column no cell owns and no rowspan covers still gets a box, so a short row does
                    // not end in blank space; a covered column gets none, so a merge has no inner border.
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
 * Lays children on a grid of [columnWidthsDp]; each child names its cells with [gridCell]. Rows
 * sized by intrinsics (a span grows its last row), then each child measured once at its drawn size.
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
    // A shaded cell keeps its fill with readable text; a white-paper fill is dropped for the surface.
    val fill = cell?.background?.takeIf { !isPaperWhite(it) }?.let(::rgbColor)
    Box(
        modifier
            .padding(CELL_GAP)
            .background(fill ?: MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
            .padding(CELL_PADDING),
    ) {
        if (cell == null) return@Box
        val shaded = if (fill != null) style.copy(color = readableOn(fill)) else style
        val lineStyle = if (cell.align == BlockAlign.START) shaded else shaded.copy(textAlign = cell.align.toTextAlign())
        // Alignment only means something once the text has the cell's width to align within.
        val fullWidth = if (cell.align == BlockAlign.START) Modifier else Modifier.fillMaxWidth()
        Column(fullWidth, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // The extractor joins a cell's paragraphs with newlines; re-splitting here makes spacing
            // independent of font size, each line its own text for match numbering.
            var before = 0
            officeCellRichLines(cell).forEach { line ->
                FindText(
                    text = line.text,
                    highlight = highlight?.skipping(before),
                    style = lineStyle,
                    modifier = fullWidth,
                    reveal = reveal,
                    spans = line.spans,
                    background = fill,
                )
                if (highlight != null) before += countOccurrences(line.text, highlight.query)
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
            is ImageState.Loaded -> {
                // Drawn at the size the document gave it, never wider than the screen: a logo stays a
                // logo instead of being stretched to fill the page.
                val declared = block.widthDp
                Image(
                    // No recycle: Compose may still be drawing this bitmap when the composition leaves.
                    bitmap = remember(current.bitmap) { current.bitmap.asImageBitmap() },
                    contentDescription = block.altText,
                    contentScale = ContentScale.FillWidth,
                    modifier = if (declared != null) Modifier.widthIn(max = declared.dp).fillMaxWidth() else Modifier.fillMaxWidth(),
                )
            }
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
