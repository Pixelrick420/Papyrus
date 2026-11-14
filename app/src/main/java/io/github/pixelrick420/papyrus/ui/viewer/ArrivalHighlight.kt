package io.github.pixelrick420.papyrus.ui.viewer

import androidx.compose.runtime.Immutable
import io.github.pixelrick420.papyrus.data.SearchQuery

internal const val ARRIVAL_HOLD_MILLIS = 2_000L

internal const val ARRIVAL_FADE_MILLIS = 350L

// The timing lives in the ViewModel, so a rotation neither restarts nor cancels the fade.
@Immutable
data class ArrivalHighlight(
    val term: String,
    val fading: Boolean = false,
    val offset: Int? = null,
)

// Null when [hasContentHit] is false (a title-only match has no occurrence to land on) or [query] has no searchable word.
internal fun focusTermFor(query: String, hasContentHit: Boolean): String? =
    if (!hasContentHit) null else SearchQuery.parse(query)?.terms?.firstOrNull()
