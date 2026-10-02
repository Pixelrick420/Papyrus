package com.papyrus.app.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.papyrus.app.R
import com.papyrus.app.viewer.OfficeBlock
import com.papyrus.app.viewer.OfficeCell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** EMF and WMF have no Android decoder, so a bad image shows a placeholder, not a dead document. */
@Composable
fun OfficeBlockView(block: OfficeBlock, modifier: Modifier = Modifier, highlight: FindHighlight? = null) {
    when (block) {
        is OfficeBlock.Heading -> Text(
            remember(block.text, highlight) { highlightedText(block.text, highlight) },
            style = headingStyle(block.level),
            modifier = modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        is OfficeBlock.Paragraph -> Text(
            remember(block.text, highlight) { highlightedText(block.text, highlight) },
            style = MaterialTheme.typography.bodyLarge,
            modifier = modifier.padding(vertical = 4.dp),
        )
        is OfficeBlock.Table -> OfficeTableView(block, modifier.padding(vertical = 8.dp), highlight)
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

/** Column is the layout unit: merged cells span several. Rowspan needs [SubcomposeLayout]. */
@Composable
private fun OfficeTableView(table: OfficeBlock.Table, modifier: Modifier = Modifier, highlight: FindHighlight? = null) {
    val columnCount = remember(table) { table.rows.maxOfOrNull { row -> row.maxOf { it.lastColumn } + 1 } ?: 0 }
    if (columnCount == 0) return
    val hScroll = rememberScrollState()
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

    Column(
        modifier
            .fillMaxWidth()
            .horizontalScroll(hScroll),
    ) {
        table.rows.forEachIndexed { rowIndex, row ->
            Row(Modifier.width(COLUMN_WIDTH * columnCount)) {
                repeat(columnCount) { column ->
                    val owner = row.firstOrNull { column in it.column..it.lastColumn }
                    when {
                        owner != null ->
                            CellBox(
                                cell = owner,
                                width = COLUMN_WIDTH * (owner.lastColumn - owner.column + 1),
                                highlight = highlight,
                            )
                        // Covered column: no box, so the merge has no internal border. The width is
                        // reserved, which aligns later cells with the rows above.
                        (rowIndex to column) in covered -> Spacer(Modifier.width(COLUMN_WIDTH))
                        // An uncovered column is a real gap in the source, so it still needs a box
                        // or the cells after it reflow leftward and misalign.
                        else -> CellBox(cell = null, width = COLUMN_WIDTH, highlight = null)
                    }
                }
            }
        }
    }
}

private val COLUMN_WIDTH = 150.dp
private val CELL_PADDING = 8.dp
private val CELL_GAP = 1.dp
private val MIN_CELL_HEIGHT = 40.dp

/** Gaps and borders are per-box, so a merged cell shows no internal border. */
@Composable
private fun CellBox(cell: OfficeCell?, width: Dp, highlight: FindHighlight?) {
    Column(
        Modifier
            .width(width)
            // A rowspan origin takes the plain minimum: it cannot measure the rows it covers, so a
            // taller box would push them down instead of overlapping them.
            .heightIn(min = MIN_CELL_HEIGHT)
            .padding(CELL_GAP),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp))
                .padding(CELL_PADDING),
        ) {
            if (cell == null) return@Box
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // The extractor joins a cell's paragraphs with newlines; re-splitting here makes
                // intra-cell spacing independent of the cell's font size.
                cell.text.split('\n').filter { it.isNotEmpty() }.forEach { line ->
                    Text(
                        remember(line, highlight) { highlightedText(line, highlight) },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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
