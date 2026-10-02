package com.papyrus.app.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Lets ViewModels emit localisable messages without holding a Context. */
class UiText(@StringRes val id: Int, vararg val args: Any) {
    fun asString(context: Context): String = context.getString(id, *args)
}

@Composable
fun UiText.asString(): String = asString(LocalContext.current)
