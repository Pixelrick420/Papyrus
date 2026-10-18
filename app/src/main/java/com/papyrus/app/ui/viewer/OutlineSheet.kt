package com.papyrus.app.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.papyrus.app.R
import com.papyrus.app.viewer.NO_OUTLINE_TARGET
import com.papyrus.app.viewer.OutlineEntry
import com.papyrus.app.viewer.hasChildren
import com.papyrus.app.viewer.initialFolds
import com.papyrus.app.viewer.visibleOutline

/** One level of heading depth, in the indent a title gets. */
private val OUTLINE_INDENT = 16.dp

/** A bookmark tree can nest far deeper than a phone can indent, so levels past this share the last indent. */
private const val MAX_INDENT_LEVELS = 6

/** The width of the fold control, kept as a spacer on rows without one so every title lines up. */
private val FOLD_CONTROL = 48.dp

/**
 * A document's outline as a list to jump from; [entries] are never empty (the caller hides the menu item
 * then). With [collapsible] (a PDF's bookmark tree) a row with entries beneath it gets a control to
 * fold them, and a long outline starts folded to its top level; without it (an Office document's
 * headings) every entry is shown, as before. An entry with no destination is shown but cannot be chosen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OutlineSheet(
    entries: List<OutlineEntry>,
    collapsible: Boolean,
    onSelect: (OutlineEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    // Folds are positions in the flat list, so they are only meaningful for this list.
    var folded by remember(entries) { mutableStateOf(if (collapsible) initialFolds(entries) else emptySet<Int>()) }
    val shown = remember(entries, folded) { visibleOutline(entries, folded) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            stringResource(R.string.viewer_outline_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 8.dp),
        )
        LazyColumn(Modifier.navigationBarsPadding()) {
            // The position is the key: several bookmarks may point at the same page, so the target is not unique.
            items(shown, key = { it }) { position ->
                val entry = entries[position]
                OutlineRow(
                    entry = entry,
                    collapsible = collapsible,
                    hasChildren = collapsible && hasChildren(entries, position),
                    isFolded = position in folded,
                    onToggle = { folded = if (position in folded) folded - position else folded + position },
                    onSelect = { onSelect(entry) },
                )
            }
        }
    }
}

@Composable
private fun OutlineRow(
    entry: OutlineEntry,
    collapsible: Boolean,
    hasChildren: Boolean,
    isFolded: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
) {
    val selectable = entry.index != NO_OUTLINE_TARGET
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = selectable, onClick = onSelect)
            // A tree row starts nearer the edge because its fold control is the first thing in it.
            .padding(start = (if (collapsible) 12.dp else 24.dp) + OUTLINE_INDENT * minOf(entry.depth, MAX_INDENT_LEVELS), end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (collapsible) {
            if (hasChildren) {
                IconButton(onClick = onToggle) {
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = stringResource(
                            if (isFolded) R.string.viewer_outline_expand else R.string.viewer_outline_collapse,
                            entry.title,
                        ),
                        // The same arrow, turned to point at what a tap will reveal.
                        modifier = Modifier.rotate(if (isFolded) -90f else 0f),
                    )
                }
            } else {
                Spacer(Modifier.size(FOLD_CONTROL))
            }
        }
        Text(
            entry.title,
            style = if (entry.depth == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            color = if (selectable) Color.Unspecified else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
    }
}
