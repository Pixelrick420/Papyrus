package io.github.pixelrick420.papyrus.ui.viewer

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

internal object PdfNightMode {

    val InvertMatrix = floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Built once, on first use: a colour filter is a native object. */
    val Invert: ColorFilter by lazy { ColorFilter.colorMatrix(ColorMatrix(InvertMatrix)) }
}
