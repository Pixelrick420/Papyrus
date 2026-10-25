package com.papyrus.app.ui.viewer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.papyrus.app.R
import com.papyrus.app.data.Highlight
import com.papyrus.app.data.MAX_HIGHLIGHT_NOTE_CHARS
import com.papyrus.app.ui.components.ControlHeight

/**
 * One of the reader's marks: the page it sits on, and a note field for it. Save is the only way a
 * note is stored, so a mis-tap is dismissed rather than written; Delete removes the mark itself.
 * Everything here stays in Papyrus, and the sheet says so, since a saved copy of the PDF carries
 * highlights but not notes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfAnnotationSheet(
    mark: Highlight,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var note by remember(mark.id) { mutableStateOf(mark.note.orEmpty()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                stringResource(R.string.viewer_annotation_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .semantics { heading() },
            )
            Text(
                stringResource(R.string.viewer_form_page, mark.page + 1),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            OutlinedTextField(
                value = note,
                // The counter below states the limit, so the field itself never silently stops taking text.
                onValueChange = { note = it.take(MAX_HIGHLIGHT_NOTE_CHARS) },
                label = { Text(stringResource(R.string.viewer_annotation_field)) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(stringResource(R.string.viewer_annotation_counter, note.length, MAX_HIGHLIGHT_NOTE_CHARS))
                },
            )
            Text(
                stringResource(R.string.viewer_annotation_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(
                onClick = { onSave(note) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .heightIn(min = ControlHeight),
            ) {
                Text(stringResource(R.string.action_save))
            }
            TextButton(
                onClick = onDelete,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 12.dp)
                    .heightIn(min = ControlHeight),
            ) {
                Text(stringResource(R.string.viewer_annotation_delete))
            }
        }
    }
}
