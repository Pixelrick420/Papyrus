package com.papyrus.app.ui.icons

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/** Media skip-previous: a bar on the left, a triangle pointing left toward it. */
val SkipPrevious: ImageVector = materialIcon(name = "Filled.SkipPrevious") {
    materialPath {
        // Triangle, apex pointing left at x=9, flat edge at x=18.
        moveTo(18.0f, 6.0f)
        lineTo(9.0f, 12.0f)
        lineTo(18.0f, 18.0f)
        close()
        // Bar, left of the apex.
        moveTo(6.0f, 6.0f)
        verticalLineTo(18.0f)
        lineTo(8.0f, 18.0f)
        lineTo(8.0f, 6.0f)
        close()
    }
}
