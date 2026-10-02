package com.papyrus.app.scanner

import androidx.annotation.StringRes
import com.papyrus.app.R

enum class ScanFilter(@StringRes val label: Int) {
    ORIGINAL(R.string.filter_color),
    GRAYSCALE(R.string.filter_grayscale),
    BLACK_AND_WHITE(R.string.filter_bw),
}
