package com.papyrus.app.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.papyrus.app.R
import com.papyrus.app.viewer.BlockAlign
import com.papyrus.app.viewer.BlockDirection
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
    speaking: SpokenRange? = null,
    direction: BlockDirection = BlockDirection.LTR,
) {
    when (block) {
        is OfficeBlock.Heading -> FindText(
            text = block.text,
            highlight = highlight,
            style = headingStyle(block.level).withLayout(block.align, direction),
            modifier = modifier.padding(top = 16.dp, bottom = 4.dp).semantics { heading() },
            reveal = reveal,
            spans = block.spans,
            speaking = speaking,
        )
        is OfficeBlock.Paragraph -> WithLayoutDirection(direction) {
            // An indent is on the side the text starts from, so it moves to the right in a right-to-left block.
            FindText(
                text = block.text,
                highlight = highlight,
                style = MaterialTheme.typography.bodyLarge.withLayout(block.align, direction),
                modifier = modifier.padding(start = INDENT_STEP * block.indent, top = 4.dp, bottom = 4.dp),
                reveal = reveal,
                spans = block.spans,
                speaking = speaking,
            )
        }
        is OfficeBlock.ListItem -> OfficeListItemView(block, direction, modifier, highlight, reveal, speaking)
        is OfficeBlock.Note -> OfficeNoteView(block, direction, modifier, highlight, reveal, speaking)
        is OfficeBlock.Divider -> HorizontalDivider(modifier.padding(vertical = 12.dp))
        is OfficeBlock.Table -> OfficeTableView(
            block,
            firstRow = 0,
            rowCount = block.rows.size,
            hScroll = rememberScrollState(),
            modifier = modifier.padding(vertical = 8.dp),
            highlight = highlight,
            reveal = reveal,
        )
        is OfficeBlock.Image -> OfficeImageView(block, modifier.padding(vertical = 8.dp))
    }
}

internal fun OfficeBlock.spokenSource(): String? = when (this) {
    is OfficeBlock.Heading -> text
    is OfficeBlock.Paragraph -> text
    is OfficeBlock.ListItem -> text
    is OfficeBlock.Note -> text
    else -> null
}

private val INDENT_STEP = 16.dp

private val LIST_INDENT = 20.dp
private val MARKER_WIDTH = 20.dp

// Start and End already follow the text direction, so a right-to-left block only needs its direction set.
internal fun TextStyle.withLayout(align: BlockAlign, direction: BlockDirection): TextStyle {
    val aligned = if (align == BlockAlign.START) this else copy(textAlign = align.toTextAlign())
    return if (direction == BlockDirection.RTL) aligned.copy(textDirection = direction.toTextDirection()) else aligned
}

@Composable
private fun WithLayoutDirection(direction: BlockDirection, content: @Composable () -> Unit) {
    val layoutDirection = if (direction == BlockDirection.RTL) LayoutDirection.Rtl else LocalLayoutDirection.current
    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection, content = content)
}

/** Marker sits beside the text, not in it, so find offsets stay on the document's own words. */
@Composable
private fun OfficeListItemView(
    block: OfficeBlock.ListItem,
    direction: BlockDirection,
    modifier: Modifier,
    highlight: FindHighlight?,
    reveal: RevealTicket?,
    speaking: SpokenRange?,
) {
    WithLayoutDirection(direction) {
        Row(modifier.padding(start = LIST_INDENT * block.level, top = 2.dp, bottom = 2.dp)) {
            Text(
                block.marker,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.widthIn(min = MARKER_WIDTH).padding(end = 8.dp),
            )
            FindText(
                text = block.text,
                highlight = highlight,
                style = MaterialTheme.typography.bodyLarge.withLayout(block.align, direction),
                modifier = Modifier.weight(1f),
                reveal = reveal,
                spans = block.spans,
                speaking = speaking,
            )
        }
    }
}

@Composable
private fun OfficeNoteView(
    note: OfficeBlock.Note,
    direction: BlockDirection,
    modifier: Modifier,
    highlight: FindHighlight?,
    reveal: RevealTicket?,
    speaking: SpokenRange?,
) {
    WithLayoutDirection(direction) {
        Row(modifier.padding(vertical = 2.dp)) {
            Text(
                note.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = MARKER_WIDTH).padding(end = 6.dp),
            )
            Column(Modifier.weight(1f)) {
                note.byline?.let { byline ->
                    Text(
                        byline,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FindText(
                    text = note.text,
                    highlight = highlight,
                    style = MaterialTheme.typography.bodySmall.withLayout(BlockAlign.START, direction),
                    modifier = Modifier.fillMaxWidth(),
                    reveal = reveal,
                    spans = note.spans,
                    speaking = speaking,
                )
            }
        }
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

// Column widths come from the cells' own content and every cell is as tall as its row; row spans need [TableGrid], which sees every row.
// Draws one band of a table split for virtualization: counting stays whole-table so match numbering equals an unsplit table's.
@Composable
internal fun OfficeTableView(
    table: OfficeBlock.Table,
    firstRow: Int,
    rowCount: Int,
    hScroll: ScrollState,
    modifier: Modifier = Modifier,
    highlight: FindHighlight? = null,
    reveal: RevealTicket? = null,
) {
    val columnCount = remember(table) { table.rows.maxOfOrNull { row -> row.maxOf { it.lastColumn } + 1 } ?: 0 }
    if (columnCount == 0) return
    // A cell needs to know how many matches came before it, since the current match is numbered across the whole table in reading order.
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
            TableGrid(columnWidthsDp = columnWidths, rowCount = rowCount) {
                for (row in 0 until rowCount) {
                    val rowIndex = firstRow + row
                    val cells = table.rows[rowIndex]
                    cells.forEachIndexed { cellIndex, cell ->
                        CellBox(
                            cell = cell,
                            style = cellStyle,
                            modifier = Modifier.gridCell(row, cell.column, cell.colspan, cell.rowspan),
                            highlight = highlight?.skipping(matchesBefore[rowIndex][cellIndex]),
                            reveal = reveal,
                        )
                    }
                    // A column no cell owns and no rowspan covers still gets a box, so a short row does
                    // not end in blank space; a covered column gets none, so a merge has no inner border.
                    repeat(columnCount) { column ->
                        val owned = cells.any { column in it.column..it.lastColumn }
                        if (!owned && (rowIndex to column) !in covered) {
                            CellBox(
                                cell = null,
                                style = cellStyle,
                                modifier = Modifier.gridCell(row, column),
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

private val CELL_CHROME = (CELL_GAP + CELL_PADDING) * 2

// `Constraints` cannot represent a height past this; a taller cell is cut short, not crashed.
private const val MAX_CELL_PX = 250_000

private fun Modifier.gridCell(row: Int, column: Int, colspan: Int = 1, rowspan: Int = 1): Modifier =
    layoutId(GridCell(row, column, colspan.coerceAtLeast(1), rowspan.coerceAtLeast(1)))

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

// Gaps are per cell, so a merged cell shows no internal border.
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
                    // A page scan keeps its shape: it fits its width at its own aspect instead of being stretched.
                    contentScale = if (block.aspectRatio != null) ContentScale.Fit else ContentScale.FillWidth,
                    // Line art that names no colour is drawn black: in the dark theme it takes the text colour.
                    colorFilter = if (block.tintable) ColorFilter.tint(MaterialTheme.colorScheme.onSurface) else null,
                    modifier = when {
                        block.aspectRatio != null -> Modifier.fillMaxWidth().aspectRatio(block.aspectRatio)
                        declared != null -> Modifier.widthIn(max = declared.dp).fillMaxWidth()
                        else -> Modifier.fillMaxWidth()
                    },
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

// From configuration, not a measured constraint: the image is `fillMaxWidth`, so measuring loops.
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
