package io.github.pixelrick420.papyrus.ui.viewer

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
import io.github.pixelrick420.papyrus.data.LocationKind
import io.github.pixelrick420.papyrus.data.PositionSample
import io.github.pixelrick420.papyrus.data.ReadingLocation
import io.github.pixelrick420.papyrus.data.RestoreRequest
import io.github.pixelrick420.papyrus.data.fractionWithin
import io.github.pixelrick420.papyrus.data.offsetWithin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

// A class so each request is its own event: two jumps to the same place with a scroll in between must scroll twice.
// A location of another kind is ignored and one past the end clamped, so a stale bookmark never scrolls to nowhere.
class ViewerJump(val location: ReadingLocation, val revealOnly: Boolean = false)

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

// Keyed on the request alone, so re-chunking the text does not scroll the reader back.
@Composable
internal fun rememberRestoredTarget(kind: LocationKind, unitCount: Int, position: PositionHooks): ReadingLocation? =
    remember(position.restore) {
        position.restore?.location?.takeIf { it.kind == kind }?.clampedTo(unitCount)
    }

// [blockOfUnit] maps back to what a location stores for lists where one stored unit spans several items (a table's bands). Reads snapshot state only, so it is safe inside `snapshotFlow`.
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

// Shared by the restore and every jump, so the two cannot drift apart.
internal suspend fun LazyListState.scrollToLocation(target: ReadingLocation) {
    scrollToItem(target.unit)
    if (target.fraction > 0f) {
        val size = snapshotFlow {
            layoutInfo.visibleItemsInfo.firstOrNull { it.index == target.unit }?.size ?: 0
        }.first { it > 0 }
        scrollToItem(target.unit, offsetWithin(target.fraction, size))
    }
}

private fun ViewerJump?.targetFor(kind: LocationKind, unitCount: Int): ReadingLocation? =
    this?.location?.takeIf { it.kind == kind }?.clampedTo(unitCount)

// The list is created already at the target item, so there is no top-of-document flash; the in-item offset needs the measured size.
// [unitOfBlock] and [blockOfUnit] are the two halves of one mapping for lists where a stored unit spans several items.
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

// A ScrollState reports Int.MAX_VALUE until the first measure, so wait that out; a document that now fits measures 0.
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
