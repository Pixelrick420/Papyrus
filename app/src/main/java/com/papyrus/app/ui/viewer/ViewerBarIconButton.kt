package com.papyrus.app.ui.viewer

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The icon button both in-flow viewer bars draw with — find and read-aloud — so they share one tint
 * and one disabled treatment: a muted glyph that dims rather than vanishes when it has nothing to do.
 */
@Composable
internal fun ViewerBarIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconSize: Dp = 24.dp,
) {
    ViewerBarIconButton(
        painter = rememberVectorPainter(icon),
        contentDescription = contentDescription,
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        iconSize = iconSize,
    )
}

/**
 * The same button for an icon that is a drawable resource. [activeTint] is for a toggle that is on,
 * so its state shows in the colour as well as the glyph; a disabled button ignores it and dims.
 */
@Composable
internal fun ViewerBarIconButton(
    painter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    iconSize: Dp = 24.dp,
    activeTint: Color? = null,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = if (enabled) {
                activeTint ?: MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
            },
            modifier = Modifier.size(iconSize),
        )
    }
}

private const val DISABLED_ALPHA = 0.38f
