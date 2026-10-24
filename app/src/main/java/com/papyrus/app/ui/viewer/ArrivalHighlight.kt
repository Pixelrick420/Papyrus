package com.papyrus.app.ui.viewer

import androidx.compose.runtime.Immutable
import com.papyrus.app.data.SearchQuery

/** How long the match an arrival landed on stays lit before it starts to fade. */
internal const val ARRIVAL_HOLD_MILLIS = 2_000L

/** How long the highlight takes to fade out once the hold is over. */
internal const val ARRIVAL_FADE_MILLIS = 350L

/**
 * A content-search result arriving in its document: [term] is the word to land on, and [fading]
 * says the hold is over and the mark is on its way out. The timing lives in the ViewModel, so a
 * rotation neither restarts nor cancels the fade.
 */
@Immutable
data class ArrivalHighlight(
    val term: String,
    val fading: Boolean = false,
    /** Markdown only: the first match's character offset, which is all that surface has to scroll to. */
    val offset: Int? = null,
)

/**
 * The word an arrival should land on: the first searchable word of [query], in the case it was
 * typed. Null when [hasContentHit] is false — a title-only match has no occurrence to land on —
 * and when [query] has no searchable word at all.
 */
internal fun focusTermFor(query: String, hasContentHit: Boolean): String? =
    if (!hasContentHit) null else SearchQuery.parse(query)?.terms?.firstOrNull()
