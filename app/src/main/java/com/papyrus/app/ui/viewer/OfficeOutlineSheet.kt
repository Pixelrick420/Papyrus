package com.papyrus.app.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.papyrus.app.R
import com.papyrus.app.viewer.OutlineEntry

/**
 * A request to scroll the document to the block at [index]. A class rather than a bare number so each
 * tap is a new request: choosing the same heading twice, with a scroll in between, must scroll again.
 */
class OutlineJump(val index: Int)

/** One level of heading depth, in the indent a title gets. */
private val OUTLINE_INDENT = 16.dp

/** The document's headings as a list to jump from; [entries] are never empty (the caller hides the menu item then). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OfficeOutlineSheet(
    entries: List<OutlineEntry>,
    onSelect: (OutlineEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            stringResource(R.string.viewer_outline_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
        )
        LazyColumn(Modifier.navigationBarsPadding()) {
            items(entries, key = { it.index }) { entry ->
                Text(
                    entry.title,
                    style = if (entry.depth == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(entry) }
                        .padding(start = 24.dp + OUTLINE_INDENT * entry.depth, end = 24.dp, top = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}
