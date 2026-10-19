package com.papyrus.app.ui.viewer

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
