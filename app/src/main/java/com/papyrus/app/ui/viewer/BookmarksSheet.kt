package com.papyrus.app.ui.viewer

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.papyrus.app.R
import com.papyrus.app.data.Bookmark
import com.papyrus.app.data.LocationSummary
import com.papyrus.app.data.MAX_BOOKMARK_LABEL_CHARS
import com.papyrus.app.data.SummaryKind
import com.papyrus.app.data.summary
import kotlinx.coroutines.launch

/**
 * "Page 12", "Line 340" or "45%" in words. One place for the viewer's default label and the sheet's
 * location line, so a bookmark that was never renamed reads the same in both.
 */
internal fun LocationSummary.asText(context: Context): String = context.getString(
    when (kind) {
        SummaryKind.PAGE -> R.string.bookmark_summary_page
        SummaryKind.LINE -> R.string.bookmark_summary_line
        SummaryKind.PERCENT -> R.string.bookmark_summary_percent
    },
    number,
)

/**
 * The document's bookmarks in document order; tapping one jumps there and closes the sheet. [unitCount] is
 * the size of what is open, which only the percentage summaries need. Delete takes effect at once and
 * offers Undo from a snackbar of the sheet's own: the viewer's sits behind a modal sheet, out of sight.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookmarksSheet(
    bookmarks: List<Bookmark>,
    unitCount: Int,
    onJump: (Bookmark) -> Unit,
    onRename: (Bookmark, String) -> Unit,
    onDelete: (Bookmark) -> Unit,
    onUndoDelete: (Bookmark) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<Bookmark?>(null) }
    val deletedMessage = stringResource(R.string.bookmark_deleted)
    val undoLabel = stringResource(R.string.action_undo)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            stringResource(R.string.bookmarks_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 8.dp)
                .semantics { heading() },
        )
        Box(Modifier.fillMaxWidth()) {
            if (bookmarks.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp)) {
                    Text(stringResource(R.string.bookmarks_empty_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.bookmarks_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else {
                LazyColumn(Modifier.navigationBarsPadding()) {
                    items(bookmarks, key = { it.id }) { bookmark ->
                        BookmarkRow(
                            bookmark = bookmark,
                            unitCount = unitCount,
                            onJump = { onJump(bookmark) },
                            onRename = { renaming = bookmark },
                            onDelete = {
                                onDelete(bookmark)
                                scope.launch {
                                    // The host queues messages; a newer delete replaces the one showing, whose Undo
                                    // is then gone and its delete final, so the bar always speaks of the latest.
                                    snackbar.currentSnackbarData?.dismiss()
                                    val result = snackbar.showSnackbar(
                                        message = deletedMessage,
                                        actionLabel = undoLabel,
                                        duration = SnackbarDuration.Long,
                                    )
                                    if (result == SnackbarResult.ActionPerformed) onUndoDelete(bookmark)
                                }
                            },
                        )
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }

    renaming?.let { target ->
        RenameBookmarkDialog(
            current = target.label,
            onConfirm = { typed ->
                onRename(target, typed)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    unitCount: Int,
    onJump: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val where = remember(bookmark.location, unitCount) { bookmark.location.summary(unitCount).asText(context) }
    val created = remember(bookmark.createdAt) {
        DateUtils.formatDateTime(
            context,
            bookmark.createdAt,
            DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.bookmark_jump), onClick = onJump)
            .padding(start = 24.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(
                bookmark.label,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.bookmark_row_detail, where, created),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRename) {
            Icon(
                Icons.Default.Edit,
                contentDescription = stringResource(R.string.bookmark_rename_cd, bookmark.label),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                painter = painterResource(R.drawable.ic_remove),
                contentDescription = stringResource(R.string.bookmark_delete_cd, bookmark.label),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Edits a label. What is typed is the reader's own and the only free text a bookmark ever holds; the
 * viewer's own labels are positions. Emptying the field falls back to the position (see
 * `cleanBookmarkLabel`), and the keyboard's Done saves.
 */
@Composable
private fun RenameBookmarkDialog(
    current: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(current) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bookmark_rename_title)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it.take(MAX_BOOKMARK_LABEL_CHARS) },
                singleLine = true,
                label = { Text(stringResource(R.string.bookmark_rename_label)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(input) }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(input) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
