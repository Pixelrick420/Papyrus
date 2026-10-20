package com.papyrus.app.ui.viewer

/*
 * How the page follows the voice. The line being read may sit anywhere from just under the top edge
 * down to [FOLLOW_LINE_FRACTION] of the viewport without anything moving; once it passes that, the page
 * scrolls just enough to bring it back, so reading down a page ends in one small, smooth scroll per
 * line with the line held near the upper part of the screen and the text still to come below it.
 * A line above the top edge, or far away after the reader has been looking elsewhere, is brought
 * back to the top margin in one move.
 */

/** The lowest the line being read may sit before the page scrolls, as a fraction of the viewport. */
internal const val FOLLOW_LINE_FRACTION = 0.4f

/** The most of the viewport the room above the line may take, so a short viewport still fits one. */
internal const val FOLLOW_TOP_MAX_FRACTION = 0.25f

/** The part of the page that has to be on screen for a line to count as followed, either side of its top. */
internal class FollowWindow(val above: Float, val below: Float)

/**
 * The window around a line of [lineHeight] in a [viewport] this tall: [topMargin] above it (less on a
 * short viewport), and down to the line's lowest allowed place below it. Zero lines is a block, such
 * as a table, followed by its top.
 */
internal fun followWindow(lineHeight: Float, viewport: Float, topMargin: Float): FollowWindow {
    if (viewport <= 0f) return FollowWindow(above = 0f, below = lineHeight)
    val above = topMargin.coerceIn(0f, viewport * FOLLOW_TOP_MAX_FRACTION)
    val below = maxOf(lineHeight, viewport * (1f - FOLLOW_LINE_FRACTION)).coerceAtMost(viewport - above)
    return FollowWindow(above, below)
}

/**
 * How far to scroll to bring the [window] around a line into a [viewport], given the line's top
 * [lineTop] measured from the viewport's top. Positive moves later text up into view; zero when the
 * window already fits, which is what keeps the page still while the line moves inside the zone.
 */
internal fun followScrollDelta(lineTop: Float, window: FollowWindow, viewport: Float): Float {
    val top = lineTop - window.above
    val bottom = lineTop + window.below
    return when {
        top < 0f -> top
        bottom > viewport -> bottom - viewport
        else -> 0f
    }
}
