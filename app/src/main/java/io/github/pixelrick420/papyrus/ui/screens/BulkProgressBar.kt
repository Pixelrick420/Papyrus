package io.github.pixelrick420.papyrus.ui.screens

import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import io.github.pixelrick420.papyrus.R

@Composable
internal fun BulkProgress.label(): String = stringResource(
    when (action) {
        BulkAction.REMOVE -> R.string.home_bulk_removing
        BulkAction.SHARE -> R.string.home_bulk_sharing
    },
    done,
    total,
)

// Drawn for the eyes only: TalkBack announces a moving progress bar, so a 40-file remove would be read out as a running tally; the snackbar announces the end.
@Composable
internal fun BulkProgressBar(progress: BulkProgress, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { progress.fraction },
        modifier = modifier.clearAndSetSemantics { },
    )
}
