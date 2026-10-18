package com.papyrus.app.ui.viewer

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.papyrus.app.R
import com.papyrus.app.viewer.parseGoToNumber

/** More digits than any page count has; stops a held key from growing the field without end. */
private const val MAX_DIGITS = 9

/**
 * Asks for a number from 1 to [max] and hands it to [onGo]. One dialog for every "go to" the viewer
 * has: the caller supplies the words ([title], [label], [error], which is given [max]) and what the
 * number means. The count is shown beside the field ("of 200"), the error appears as soon as what is
 * typed cannot be accepted, and the keyboard's Go key does what the button does.
 */
@Composable
internal fun GoToDialog(
    title: String,
    label: String,
    error: String,
    max: Int,
    onGo: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    // An empty field is not an error until Go is pressed on it.
    var attempted by remember { mutableStateOf(false) }
    val value = parseGoToNumber(input, max)
    val showError = value == null && (attempted || input.isNotEmpty())
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    val submit: () -> Unit = {
        if (value != null) onGo(value) else attempted = true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = input,
                // Digits only: a stray sign or separator never reaches the parser.
                onValueChange = { input = it.filter { c -> c in '0'..'9' }.take(MAX_DIGITS) },
                singleLine = true,
                isError = showError,
                label = { Text(label) },
                suffix = { Text(stringResource(R.string.viewer_goto_of, max)) },
                supportingText = if (showError) ({ Text(error) }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = submit) { Text(stringResource(R.string.viewer_goto_go)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
