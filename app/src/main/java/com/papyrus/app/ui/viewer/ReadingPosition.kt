package com.papyrus.app.ui.viewer

import android.text.Layout
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import com.papyrus.app.data.LocationKind
import com.papyrus.app.data.PositionSample
import com.papyrus.app.data.ReadingLocation
import com.papyrus.app.data.RestoreRequest
import com.papyrus.app.data.fractionWithin
import com.papyrus.app.data.offsetWithin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * A request to scroll a viewer to [location], from a link, the outline, go to page or a bookmark: the one
 * jump mechanism of every format. A class rather than a bare location so each request is its own event:
 * two jumps to the same place, with a scroll in between, must scroll twice. A location of another kind
 * than the viewer's is ignored, and one past the end is clamped, so a stale bookmark can never scroll
 * to nowhere. [revealOnly] moves only if the place is off screen (a link tapped on the page it names);
 * otherwise the place's top meets the top of the viewport.
 */
class ViewerJump(val location: ReadingLocation, val revealOnly: Boolean = false)

/**
 * How a viewer takes part in reading-position memory and jumps, as one parameter instead of four: [restore]
 * says where to open, [onRestored] opens the save gate once applied, [onPosition] reports later changes,
 * and [jump] is the latest request to scroll somewhere else.
 */
@Immutable
class PositionHooks(
    val restore: RestoreRequest?,
    val onRestored: (PositionSample) -> Unit,
    val onPosition: (PositionSample) -> Unit,
    val jump: ViewerJump? = null,
) {
    companion object {
        val None = PositionHooks(restore = null, onRestored = {}, onPosition = {})
    }
}

/**
 * Where to put a viewer of [unitCount] units, taken once per restore request and clamped to what
 * loaded. Keyed on the request alone, so re-chunking the text does not scroll the reader back.
 */
@Composable
internal fun rememberRestoredTarget(kind: LocationKind, unitCount: Int, position: PositionHooks): ReadingLocation? =
    remember(position.restore) {
        position.restore?.location?.takeIf { it.kind == kind }?.clampedTo(unitCount)
    }

/**
 * The top of a lazy list as a location: the first visible item and how far into it the viewport is.
 * Null while nothing is laid out. Reads snapshot state only, so it is safe inside `snapshotFlow`.
 *
 * [blockOfUnit] maps (item, fraction) back to what a location stores, for lists where one stored
 * unit spans several items — a table's bands. Without it, the item *is* the unit, unchanged.
 */
internal fun LazyListState.positionSample(
    kind: LocationKind,
    unitCount: Int,
    scale: Float,
    blockOfUnit: ((unit: Int, fraction: Float) -> Pair<Int, Float>)? = null,
): PositionSample? {
    val index = firstVisibleItemIndex
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
    val within = fractionWithin(firstVisibleItemScrollOffset, item.size)
    val (unit, fraction) = blockOfUnit?.invoke(index, within) ?: (index to within)
    // A row after the last unit (the "truncated" or "incomplete" notice) is not a place in the document; it
    // reads as the end of the last unit rather than as a location that does not exist.
    val location = ReadingLocation(kind, unit, fraction).clampedTo(unitCount)
    return PositionSample(location, unitCount, atEnd = !canScrollForward, zoom = scale)
}

/**
 * Puts the top of the viewport at [target]: the item, then the in-item offset, which needs the item's
 * measured size. Shared by the restore and by every jump, so the two cannot drift apart.
 */
internal suspend fun LazyListState.scrollToLocation(target: ReadingLocation) {
    scrollToItem(target.unit)
    if (target.fraction > 0f) {
        val size = snapshotFlow {
            layoutInfo.visibleItemsInfo.firstOrNull { it.index == target.unit }?.size ?: 0
        }.first { it > 0 }
        scrollToItem(target.unit, offsetWithin(target.fraction, size))
    }
}

/** The jump's place if it is for a viewer of [kind], clamped to [unitCount] units; else null. */
private fun ViewerJump?.targetFor(kind: LocationKind, unitCount: Int): ReadingLocation? =
    this?.location?.takeIf { it.kind == kind }?.clampedTo(unitCount)

/**
 * Restores [target] in a lazy list, then reports every position the list shows. The list is created
 * already at the target item, so no top-of-document flash; the in-item offset needs the measured size.
 *
 * [unitOfBlock] and [blockOfUnit] are the two halves of one mapping between a stored location and
 * the list's items, for lists where one stored unit spans several items (a table's bands): restore
 * splits the stored (unit, fraction) across the items, sampling folds the visible item back. Both
 * default to null — the identity mapping — which is every other viewer.
 * A [PositionHooks.jump] scrolls the same list later, to any place of the document.
 */
@Composable
internal fun LazyPositionEffects(
    listState: LazyListState,
    kind: LocationKind,
    unitCount: Int,
    zoom: ZoomState,
    position: PositionHooks,
    target: ReadingLocation?,
    unitOfBlock: ((block: Int, fraction: Float) -> Pair<Int, Float>)? = null,
    blockOfUnit: ((unit: Int, fraction: Float) -> Pair<Int, Float>)? = null,
) {
    val hooks by rememberUpdatedState(position)
    val restore by rememberUpdatedState(unitOfBlock)
    val sample by rememberUpdatedState(blockOfUnit)

    LaunchedEffect(listState, target) {
        if (target == null) return@LaunchedEffect
        val (unit, fraction) = restore?.invoke(target.unit, target.fraction) ?: (target.unit to target.fraction)
        listState.scrollToLocation(ReadingLocation(kind, unit, fraction))
        withFrameNanos { }
        val applied = listState.positionSample(kind, unitCount, zoom.committedScale, sample)
            ?: PositionSample(target, unitCount, atEnd = false, zoom = zoom.committedScale)
        hooks.onRestored(applied)
    }

    val jump = position.jump
    LaunchedEffect(listState, jump) {
        val to = jump.targetFor(kind, unitCount) ?: return@LaunchedEffect
        val (unit, fraction) = restore?.invoke(to.unit, to.fraction) ?: (to.unit to to.fraction)
        if (jump?.revealOnly == true) listState.scrollToItemUnlessVisible(unit) else listState.scrollToLocation(ReadingLocation(kind, unit, fraction))
    }

    LaunchedEffect(listState, kind, unitCount) {
        snapshotFlow { listState.positionSample(kind, unitCount, zoom.committedScale, sample) }
            .filterNotNull()
            .collect { hooks.onPosition(it) }
    }
}

/**
 * Same for Markdown: one TextView inside one scroll, so the anchor is the character offset of the line
 * at the viewport top. [layout] is null until measured; [contentTopPx] is the padding above the text.
 */
@Composable
internal fun MarkdownPositionEffects(
    scroll: ScrollState,
    layout: () -> Layout?,
    textLength: Int,
    zoom: ZoomState,
    position: PositionHooks,
    target: ReadingLocation?,
    contentTopPx: Float,
) {
    val hooks by rememberUpdatedState(position)

    LaunchedEffect(scroll, target) {
        if (target == null) return@LaunchedEffect
        scrollToOffset(scroll, layout, target.unit, contentTopPx)
        withFrameNanos { }
        val applied = markdownSample(scroll, layout(), textLength, zoom.committedScale, contentTopPx)
            ?: PositionSample(target, textLength, atEnd = false, zoom = zoom.committedScale)
        hooks.onRestored(applied)
    }

    val jump = position.jump
    LaunchedEffect(scroll, jump) {
        val to = jump.targetFor(LocationKind.CHAR, textLength) ?: return@LaunchedEffect
        // A restore opens at the top already, but a jump to the start has to bring the reader back to it.
        if (to.unit <= 0) scroll.scrollTo(0) else scrollToOffset(scroll, layout, to.unit, contentTopPx)
    }

    LaunchedEffect(scroll, textLength) {
        snapshotFlow { markdownSample(scroll, layout(), textLength, zoom.committedScale, contentTopPx) }
            .filterNotNull()
            .collect { hooks.onPosition(it) }
    }
}

/**
 * Scrolls so the line holding character [offset] is at the top. Offset 0 is already there; a later one
 * needs the text laid out. A ScrollState reports Int.MAX_VALUE until the first measure, so wait that out;
 * a document that now fits measures 0.
 */
private suspend fun scrollToOffset(scroll: ScrollState, layout: () -> Layout?, offset: Int, contentTopPx: Float) {
    if (offset <= 0) return
    snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }
    val textLayout = layout() ?: return
    val line = textLayout.getLineForOffset(offset.coerceIn(0, textLayout.text.length))
    scroll.scrollTo((textLayout.getLineTop(line) + contentTopPx).roundToInt())
}

private fun markdownSample(
    scroll: ScrollState,
    layout: Layout?,
    textLength: Int,
    scale: Float,
    contentTopPx: Float,
): PositionSample? {
    if (layout == null || textLength <= 0 || layout.lineCount == 0) return null
    val y = (scroll.value - contentTopPx).coerceAtLeast(0f).roundToInt()
    val offset = layout.getLineStart(layout.getLineForVertical(y))
    val atEnd = scroll.maxValue > 0 && scroll.value >= scroll.maxValue
    return PositionSample(ReadingLocation(LocationKind.CHAR, offset), textLength, atEnd, scale)
}
