package com.papyrus.app.ui.viewer

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

/** Night mode for the PDF viewer: a page's colours are inverted, so white paper reads black. */
internal object PdfNightMode {

    /** Flips the colour channels and leaves alpha alone: the platform's own inversion matrix. */
    val InvertMatrix = floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** Built once, on first use: a colour filter is a native object. */
    val Invert: ColorFilter by lazy { ColorFilter.colorMatrix(ColorMatrix(InvertMatrix)) }
}
