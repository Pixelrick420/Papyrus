package com.papyrus.app.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.papyrus.app.R
import com.papyrus.app.ui.components.ControlHeight
import com.papyrus.app.viewer.PdfFieldKind
import com.papyrus.app.viewer.PdfFormField

/**
 * The document's form widgets, in page order, with the value each one holds now. Edits apply to the
 * open document only; saving is a separate, explicit copy through the system picker, so the reader's
 * file is never written to. A check box or radio button switches on tap; a text or choice field opens
 * a dialog for its value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PdfFormSheet(
    fields: List<PdfFormField>,
    onSetText: (PdfFormField, String) -> Unit,
    onChoose: (PdfFormField, List<Int>) -> Unit,
    onToggle: (PdfFormField) -> Unit,
    onSaveCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    var editing by remember { mutableStateOf<PdfFormField?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Text(
            stringResource(R.string.viewer_form_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 4.dp)
                .semantics { heading() },
        )
        Text(
            stringResource(R.string.viewer_form_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 12.dp),
        )
        // fill = false keeps the sheet short for a small form; a long one scrolls within it instead.
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(fields, key = { it.key }) { field ->
                FormFieldRow(
                    field = field,
                    onEdit = { editing = field },
                    onToggle = { onToggle(field) },
                )
            }
        }
        Button(
            onClick = onSaveCopy,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 24.dp, top = 12.dp, end = 24.dp, bottom = 12.dp)
                .heightIn(min = ControlHeight),
        ) {
            Text(stringResource(R.string.viewer_form_save))
        }
    }

    editing?.let { field ->
        when (field.kind) {
            PdfFieldKind.TEXT -> FormTextDialog(
                field = field,
                onConfirm = { text ->
                    onSetText(field, text)
                    editing = null
                },
                onDismiss = { editing = null },
            )
            PdfFieldKind.CHOICE -> FormChoiceDialog(
                field = field,
                onConfirm = { selected ->
                    onChoose(field, selected)
                    editing = null
                },
                onDismiss = { editing = null },
            )
            // Only the two kinds above open a dialog, so this branch is not reached.
            else -> Unit
        }
    }
}

/** One widget: its label, what it holds, and the control that changes it. */
@Composable
private fun FormFieldRow(
    field: PdfFormField,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
) {
    val page = stringResource(R.string.viewer_form_page, field.page + 1)
    val empty = stringResource(R.string.viewer_form_value_empty)
    val readOnly = stringResource(R.string.viewer_form_read_only)
    val value = when (field.kind) {
        PdfFieldKind.CHOICE -> field.selectedOptions
            .mapNotNull { field.options.getOrNull(it) }
            .joinToString()
            .ifEmpty { field.value }
        else -> field.value
    }
    val detail = if (!field.writable) {
        stringResource(R.string.viewer_form_row_detail, page, readOnly)
    } else if (field.kind.toggles) {
        page
    } else {
        stringResource(R.string.viewer_form_row_detail, page, value.ifBlank { empty })
    }
    // A choice with no options has nothing to choose from, so its row only reads.
    val opensDialog = field.writable && when (field.kind) {
        PdfFieldKind.TEXT -> true
        PdfFieldKind.CHOICE -> field.options.isNotEmpty()
        else -> false
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = opensDialog,
                onClickLabel = stringResource(R.string.viewer_form_edit),
                onClick = onEdit,
            )
            // One stop per field: the label, the value and the control read together.
            .semantics(mergeDescendants = true) {}
            .padding(start = 24.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(
                text = field.label ?: stringResource(R.string.viewer_form_field, field.page + 1),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when (field.kind) {
            PdfFieldKind.CHECKBOX -> Checkbox(
                checked = field.checked,
                onCheckedChange = if (field.writable) { _ -> onToggle() } else null,
            )
            PdfFieldKind.RADIO -> RadioButton(
                selected = field.checked,
                onClick = if (field.writable) onToggle else null,
            )
            else -> Unit
        }
    }
}

/** Edits a text field. Multi-line widgets keep their line breaks; the keyboard's Done saves a one-line one. */
@Composable
private fun FormTextDialog(
    field: PdfFormField,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember(field.key) { mutableStateOf(field.value) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(field.label ?: stringResource(R.string.viewer_form_field, field.page + 1)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = !field.multiline,
                minLines = if (field.multiline) MULTILINE_FIELD_LINES else 1,
                keyboardOptions = KeyboardOptions(imeAction = if (field.multiline) ImeAction.Default else ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(input) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(input) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Picks from a choice field's list: radio buttons when the document allows one, check boxes when it
 * allows several. Nothing is changed until Save, so a wrong tap is only dismissed.
 */
@Composable
private fun FormChoiceDialog(
    field: PdfFormField,
    onConfirm: (List<Int>) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember(field.key) { mutableStateOf(field.selectedOptions.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(field.label ?: stringResource(R.string.viewer_form_field, field.page + 1)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                field.options.forEachIndexed { index, option ->
                    val checked = index in selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                selected = when {
                                    field.multiSelect && checked -> selected - index
                                    field.multiSelect -> selected + index
                                    else -> setOf(index)
                                }
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (field.multiSelect) {
                            Checkbox(checked = checked, onCheckedChange = null)
                        } else {
                            RadioButton(selected = checked, onClick = null)
                        }
                        Text(
                            option,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected.sorted()) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Lines a multi-line field opens with, enough to see a few sentences without filling the dialog. */
private const val MULTILINE_FIELD_LINES = 4
