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
import androidx.compose.ui.unit.dp
import com.papyrus.app.R
import com.papyrus.app.viewer.TextEncoding
import com.papyrus.app.viewer.TextEncodings

/**
 * Lets a person re-read a text file as another charset when detection got it wrong. [detected] is the
 * encoding on screen now; [override] is the charset picked by hand, or null while detection is in charge.
 * [onSelect] gets a charset name, or null for "Auto-detect".
 */
@Composable
fun EncodingDialog(
    detected: TextEncoding?,
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
                // With a hand-picked charset on screen, "detected" is that pick, not what detection would say.
                val autoLabel = if (override == null && detected != null) {
                    stringResource(R.string.viewer_encoding_auto, labelOf(detected))
                } else {
                    stringResource(R.string.viewer_encoding_auto_plain)
                }
                EncodingRow(autoLabel, selected = override == null) { onSelect(null) }
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
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** The picker's own wording when the charset is one it lists; the charset's name otherwise, such as ISO-8859-5. */
private fun labelOf(encoding: TextEncoding): String =
    TextEncodings.choices.firstOrNull { TextEncodings.charsetOrNull(it.charsetName) == encoding.charset }?.label
        ?: encoding.charset.displayName()

private val DialogListMaxHeight = 360.dp
