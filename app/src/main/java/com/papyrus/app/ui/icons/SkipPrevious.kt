package com.papyrus.app.ui.icons

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

val SkipPrevious: ImageVector = materialIcon(name = "Filled.SkipPrevious") {
    materialPath {
        moveTo(18.0f, 6.0f)
        lineTo(9.0f, 12.0f)
        lineTo(18.0f, 18.0f)
        close()
        moveTo(6.0f, 6.0f)
        verticalLineTo(18.0f)
        lineTo(8.0f, 18.0f)
        lineTo(8.0f, 6.0f)
        close()
    }
}
