package com.papyrus.app.ui.screens

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.papyrus.app.R

/** "Removing 3 of 12": what the selection header says while a bulk action runs, in place of the count. */
@Composable
internal fun BulkProgress.label(): String = stringResource(
    when (action) {
        BulkAction.REMOVE -> R.string.home_bulk_removing
        BulkAction.SHARE -> R.string.home_bulk_sharing
    },
    done,
    total,
)

/**
 * The bar under the selection header while a bulk action runs.
 *
 * Drawn for the eyes only. A progress bar that TalkBack can see is announced as it moves, so a
 * 40-file remove would be read out as a running tally and then once more when it finished. The text
 * beside it, [label], carries the same information for anyone who moves to it, and the single
 * announcement when the action ends comes from the snackbar.
 */
@Composable
internal fun BulkProgressBar(progress: BulkProgress, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress.fraction },
        modifier = modifier.clearAndSetSemantics { },
    )
}
