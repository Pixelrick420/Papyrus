package com.papyrus.app.ui.viewer

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
// Core icons, not the extended set: this project depends on material-icons-core only.
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.papyrus.app.R
import com.papyrus.app.ui.components.SearchPill

/**
 * Find in file, drawn with the same [SearchPill] as the Home search so the two read as one control.
 *
 * The caller places this in the layout flow, above the document, not over it: the viewer is simply
 * given less height while find is open, so no part of the document is covered and a hit scrolled to
 * lands in plain view with no clearance arithmetic.
 *
 * The caller owns the query and hit list. [canNavigate] is false for a surface that highlights in
 * place, where arrows and an "n of m" position would be meaningless. [modifier] supplies the outer
 * insets; the pill and its three trailing buttons lay out inside it.
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
    // Landscape only, from the caller: hide a docked keyboard when a step moves the view, so the
    // whole page shows. A floating keyboard is left alone.
    dismissKeyboardOnStep: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = focusRequester ?: remember { FocusRequester() }
    val hasQuery = query.isNotEmpty()
    val hasMatches = matchCount > 0
    val stepEnabled = hasMatches && canNavigate

    // Opening find means the next thing the reader does is type. `runCatching`: requesting focus
    // before the field is attached throws, and a missed focus is harmless (a tap on the pill works).
    LaunchedEffect(requester) {
        runCatching { requester.requestFocus() }
        keyboard?.show()
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchPill(
            text = query,
            onTextChange = onQueryChange,
            hint = stringResource(R.string.viewer_find_hint),
            // Enter steps to the next match. Where there is no step target it does nothing.
            onSearch = {
                if (dismissKeyboardOnStep) keyboard?.hide()
                if (canNavigate) onNext()
            },
            modifier = Modifier.weight(1f),
            focusRequester = requester,
            trailing = {
                // Grows from nothing when the first character is typed, so the field narrows
                // smoothly instead of jumping.
                Box(Modifier.animateContentSize()) {
                    if (hasQuery) {
                        Text(
                            text = when {
                                !hasMatches -> stringResource(R.string.viewer_find_none)
                                canNavigate -> stringResource(R.string.viewer_find_counter, currentMatch + 1, matchCount)
                                else -> stringResource(R.string.viewer_find_total, matchCount)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (hasMatches) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
            },
        )

        FindIconButton(
            icon = Icons.Default.KeyboardArrowUp,
            contentDescription = stringResource(R.string.viewer_find_previous),
            onClick = {
                if (dismissKeyboardOnStep) keyboard?.hide()
                onPrevious()
            },
            enabled = stepEnabled,
        )
        FindIconButton(
            icon = Icons.Default.KeyboardArrowDown,
            contentDescription = stringResource(R.string.viewer_find_next),
            onClick = {
                if (dismissKeyboardOnStep) keyboard?.hide()
                onNext()
            },
            enabled = stepEnabled,
        )
        FindIconButton(
            icon = Icons.Default.Close,
            contentDescription = stringResource(R.string.viewer_find_close),
            onClick = {
                keyboard?.hide()
                onClose()
            },
            // Same 20dp close glyph as the Home clear button.
            iconSize = 20.dp,
        )
    }
}

/** Tinted like the Home pill's clear button; a disabled step button dims rather than vanishes. */
@Composable
private fun FindIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconSize: Dp = 24.dp,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
            },
            modifier = Modifier.size(iconSize),
        )
    }
}

private const val DISABLED_ALPHA = 0.38f
