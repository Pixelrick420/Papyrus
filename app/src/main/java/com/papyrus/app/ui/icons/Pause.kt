package com.papyrus.app.ui.icons

import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

/** Material "pause": two equal bars. Not in material-icons-core, so it is drawn here. */
val Pause: ImageVector = materialIcon(name = "Filled.Pause") {
    materialPath {
        moveTo(6.0f, 19.0f)
        horizontalLineTo(10.0f)
        verticalLineTo(5.0f)
        horizontalLineTo(6.0f)
        verticalLineTo(19.0f)
        close()
        moveTo(14.0f, 5.0f)
        verticalLineTo(19.0f)
        horizontalLineTo(18.0f)
        verticalLineTo(5.0f)
        horizontalLineTo(14.0f)
        close()
    }
}
