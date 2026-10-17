package com.papyrus.app.ui.viewer

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.papyrus.app.R
import com.papyrus.app.viewer.TextEncodings

/** Re-reads a text file as another charset when detection got it wrong. */
@Composable
fun EncodingDialog(
    override: String?,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_encoding_title)) },
        text = {
            // The list is long and a dialog's body does not scroll by itself.
            Column(
                Modifier
                    .heightIn(max = DialogListMaxHeight)
                    .verticalScroll(rememberScrollState())
                    .selectableGroup(),
            ) {
                EncodingRow(
                    label = stringResource(R.string.viewer_encoding_auto_plain),
                    selected = override == null,
                    onClick = { onSelect(null) },
                )
                TextEncodings.choices.forEach { choice ->
                    EncodingRow(
                        label = choice.label,
                        selected = override != null && override.equals(choice.charsetName, ignoreCase = true),
                    ) { onSelect(choice.charsetName) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun EncodingRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val DialogListMaxHeight = 360.dp
