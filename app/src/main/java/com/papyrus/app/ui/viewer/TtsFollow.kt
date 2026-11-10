package com.papyrus.app.ui.viewer

// Once the line passes [FOLLOW_LINE_FRACTION] the page scrolls just enough to bring it back, so reading down a page ends in one small, smooth scroll per line.

internal const val FOLLOW_LINE_FRACTION = 0.4f

/** The most of the viewport the room above the line may take, so a short viewport still fits one. */
internal const val FOLLOW_TOP_MAX_FRACTION = 0.25f

internal class FollowWindow(val above: Float, val below: Float)

internal fun followWindow(lineHeight: Float, viewport: Float, topMargin: Float): FollowWindow {
    if (viewport <= 0f) return FollowWindow(above = 0f, below = lineHeight)
    val above = topMargin.coerceIn(0f, viewport * FOLLOW_TOP_MAX_FRACTION)
    val below = maxOf(lineHeight, viewport * (1f - FOLLOW_LINE_FRACTION)).coerceAtMost(viewport - above)
    return FollowWindow(above, below)
}

// Zero when the window already fits, which keeps the page still while the line moves inside the zone.
internal fun followScrollDelta(lineTop: Float, window: FollowWindow, viewport: Float): Float {
    val top = lineTop - window.above
    val bottom = lineTop + window.below
    return when {
        top < 0f -> top
        bottom > viewport -> bottom - viewport
        else -> 0f
    }
}
