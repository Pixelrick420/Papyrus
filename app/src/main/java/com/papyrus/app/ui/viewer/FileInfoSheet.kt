package com.papyrus.app.ui.viewer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.papyrus.app.R
import com.papyrus.app.data.DocumentEntity
import com.papyrus.app.data.DocumentFormat
import java.text.DateFormat
import java.util.Date

/** `selectable` means "wrap fully", not "copyable": a URI has no natural break to truncate at. */
@Composable
private fun InfoRow(label: String, value: String, selectable: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (selectable) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Ellipsis,
            modifier = if (selectable) Modifier.padding(top = 2.dp) else Modifier,
        )
    }
}

/** Shows only what the app already holds, nothing probed from disk, so it cannot fail. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileInfoSheet(
    document: DocumentEntity,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                stringResource(R.string.viewer_info_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                document.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )

            HorizontalDivider()

            InfoRow(stringResource(R.string.viewer_info_name), document.title)
            InfoRow(stringResource(R.string.viewer_info_format), document.format.label)
            InfoRow(
                stringResource(R.string.viewer_info_size),
                formatSize(document.sizeBytes),
            )
            document.mimeType?.let { InfoRow(stringResource(R.string.viewer_info_type), it) }
            if (document.format == DocumentFormat.PDF) {
                InfoRow(
                    stringResource(R.string.viewer_info_pages),
                    stringResource(R.string.viewer_info_pages_value, document.pageCount),
                )
            }
            InfoRow(
                stringResource(R.string.viewer_info_opened),
                formatTimestamp(document.lastOpenedAt),
            )
            InfoRow(
                stringResource(R.string.viewer_info_added),
                formatTimestamp(document.createdAt),
            )
            InfoRow(stringResource(R.string.viewer_info_uri), document.uri, selectable = true)
        }
    }
}

@Composable
private fun formatSize(bytes: Long): String =
    android.text.format.Formatter.formatShortFileSize(LocalContext.current, bytes)

@Composable
private fun formatTimestamp(millis: Long): String {
    val formatter = DateFormat.getDateInstance(DateFormat.MEDIUM)
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    val date = Date(millis)
    return "${formatter.format(date)} · ${time.format(date)}"
}
