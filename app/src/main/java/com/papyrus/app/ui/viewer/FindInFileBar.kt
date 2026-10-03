package com.papyrus.app.ui.viewer

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
// Core icons, not the extended set: this project depends on material-icons-core only, and the
// auto-mirrored package is extended-only.
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.papyrus.app.R

/** Text field height plus its own and the host's margins: a hit scrolled to must clear the bar. */
val FindBarClearance: Dp = 80.dp

/**
 * `scrollToItem` leaves the item flush with the top edge, under the bar, and does not promise to
 * honour a negative offset, so this scrolls back by [clearancePx]. Item 0 has no room above it.
 */
suspend fun LazyListState.scrollToItemBelowFindBar(index: Int, clearancePx: Float) {
    scrollToItem(index)
    if (index > 0) scrollBy(-clearancePx)
}

/**
 * Floats at the top because at the bottom it rode over the keyboard, covering what the reader
 * had just scrolled to. The caller owns the query and hit list. [canNavigate] is false for a
 * surface that highlights in place, where arrows and an "n of m" position would be meaningless.
 */
@Composable
fun FindInFileBar(
    query: String,
    matchCount: Int,
    currentMatch: Int,
    canNavigate: Boolean,
    onQueryChange: (String) -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val hasQuery = query.isNotEmpty()
    val hasMatches = matchCount > 0
    val stepEnabled = hasMatches && canNavigate

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 8.dp,
    ) {
    // Plain `fillMaxWidth`: the caller's modifier is already on the Surface, so reusing it would
    // apply any padding or offset twice.
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .weight(1f)
                .then(focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier),
            singleLine = true,
            placeholder = { Text(stringResource(R.string.viewer_find_hint)) },
            // The same drawable as the home search field, so both searches share one icon.
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (canNavigate) onNext() }),
            textStyle = MaterialTheme.typography.bodyMedium,
        )

        if (hasQuery) {
            Text(
                text = when {
                    !hasMatches -> stringResource(R.string.viewer_find_none)
                    canNavigate -> stringResource(R.string.viewer_find_counter, currentMatch + 1, matchCount)
                    else -> stringResource(R.string.viewer_find_total, matchCount)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(72.dp),
            )
            IconButton(onClick = onPrevious, enabled = stepEnabled) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.viewer_find_previous),
                )
            }
            IconButton(onClick = onNext, enabled = stepEnabled) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.viewer_find_next),
                )
            }
        }

        IconButton(
            onClick = {
                keyboard?.hide()
                onClose()
            },
        ) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.viewer_find_close))
        }
    }
    }
}
