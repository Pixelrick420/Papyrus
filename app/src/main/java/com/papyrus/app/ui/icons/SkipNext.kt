package com.papyrus.app.ui.icons

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/** Media skip-next: a triangle pointing right, then a bar at its right. */
val SkipNext: ImageVector = materialIcon(name = "Filled.SkipNext") {
    materialPath {
        moveTo(6.0f, 6.0f)
        lineTo(15.0f, 12.0f)
        lineTo(6.0f, 18.0f)
        close()
        moveTo(17.0f, 6.0f)
        lineTo(20.0f, 6.0f)
        lineTo(20.0f, 18.0f)
        lineTo(17.0f, 18.0f)
        close()
    }
}
