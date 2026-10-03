package com.papyrus.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.papyrus.app.R

/** Search pill, Open file button and find bar share this, so every control is the same height. */
val ControlHeight = 48.dp

private const val FOCUS_ANIMATION_MS = 180

/**
 * The single search field for the app: Home's file filter and the viewer's find bar both draw it,
 * so they cannot drift apart. Drawn from the same defaults as the Open file button (shape, border,
 * transparent interior). Deliberately not Material's `SearchBar`: that fixes its own colors,
 * elevation and width rules, adds its own status-bar padding, and takes over the screen when expanded.
 *
 * [trailing] fills the end of the pill (a clear button, a match counter). It owns its own end
 * inset, so the caller decides how far the last element sits from the rounded edge.
 */
@Composable
fun SearchPill(
    text: String,
    onTextChange: (String) -> Unit,
    hint: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    iconContentDescription: String? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
    trailing: @Composable RowScope.() -> Unit = { Spacer(Modifier.width(16.dp)) },
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    // At rest the border is whatever the button's is, read from the same place.
    val restingBorder = ButtonDefaults.outlinedButtonBorder(enabled = true)
    val restingColor = (restingBorder.brush as? SolidColor)?.value ?: MaterialTheme.colorScheme.outline
    val borderColor by animateColorAsState(
        targetValue = if (focused) MaterialTheme.colorScheme.primary else restingColor,
        animationSpec = tween(FOCUS_ANIMATION_MS),
        label = "searchBorderColor",
    )
    val borderWidth by animateDpAsState(
        targetValue = if (focused) restingBorder.width + 1.dp else restingBorder.width,
        animationSpec = tween(FOCUS_ANIMATION_MS),
        label = "searchBorderWidth",
    )
    val iconColor by animateColorAsState(
        targetValue = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(FOCUS_ANIMATION_MS),
        label = "searchIconColor",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ControlHeight),
        shape = ButtonDefaults.outlinedShape,
        color = Color.Transparent,
        border = BorderStroke(borderWidth, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // The whole pill focuses the field, not just its one line of text. A raw tap detector
                // rather than `clickable`, which would merge the text field into one semantics node.
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        focusRequester.requestFocus()
                        keyboard?.show()
                    })
                }
                .padding(start = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = iconContentDescription,
                tint = iconColor,
            )
            Spacer(Modifier.width(12.dp))

            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                interactionSource = interactionSource,
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) {
                            Text(
                                text = hint,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        innerTextField()
                    }
                },
            )

            trailing()
        }
    }
}
