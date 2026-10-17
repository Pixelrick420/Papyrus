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
 * How a viewer takes part in reading-position memory, as one parameter instead of three: [restore] says
 * where to open, [onRestored] opens the save gate once applied, and [onPosition] reports later changes.
 */
@Immutable
class PositionHooks(
    val restore: RestoreRequest?,
    val onRestored: (PositionSample) -> Unit,
    val onPosition: (PositionSample) -> Unit,
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
 */
internal fun LazyListState.positionSample(kind: LocationKind, unitCount: Int, scale: Float): PositionSample? {
    val index = firstVisibleItemIndex
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return null
    val location = ReadingLocation(kind, index, fractionWithin(firstVisibleItemScrollOffset, item.size))
    return PositionSample(location, unitCount, atEnd = !canScrollForward, zoom = scale)
}

/**
 * Restores [target] in a lazy list, then reports every position the list shows. The list is created
 * already at the target item, so no top-of-document flash; the in-item offset needs the measured size.
 */
@Composable
internal fun LazyPositionEffects(
    listState: LazyListState,
    kind: LocationKind,
    unitCount: Int,
    zoom: ZoomState,
    position: PositionHooks,
    target: ReadingLocation?,
) {
    val hooks by rememberUpdatedState(position)

    LaunchedEffect(listState, target) {
        if (target == null) return@LaunchedEffect
        listState.scrollToItem(target.unit)
        if (target.fraction > 0f) {
            val size = snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == target.unit }?.size ?: 0
            }.first { it > 0 }
            listState.scrollToItem(target.unit, offsetWithin(target.fraction, size))
        }
        withFrameNanos { }
        val applied = listState.positionSample(kind, unitCount, zoom.committedScale)
            ?: PositionSample(target, unitCount, atEnd = false, zoom = zoom.committedScale)
        hooks.onRestored(applied)
    }

    LaunchedEffect(listState, kind, unitCount) {
        snapshotFlow { listState.positionSample(kind, unitCount, zoom.committedScale) }
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
        // Offset 0 is already there; only a later offset needs the text laid out. A ScrollState reports
        // Int.MAX_VALUE until the first measure, so wait that out; a document that now fits measures 0.
        if (target.unit > 0) {
            snapshotFlow { scroll.maxValue }.first { it != Int.MAX_VALUE }
            val textLayout = layout() ?: return@LaunchedEffect
            val line = textLayout.getLineForOffset(target.unit.coerceIn(0, textLayout.text.length))
            scroll.scrollTo((textLayout.getLineTop(line) + contentTopPx).roundToInt())
        }
        withFrameNanos { }
        val applied = markdownSample(scroll, layout(), textLength, zoom.committedScale, contentTopPx)
            ?: PositionSample(target, textLength, atEnd = false, zoom = zoom.committedScale)
        hooks.onRestored(applied)
    }

    LaunchedEffect(scroll, textLength) {
        snapshotFlow { markdownSample(scroll, layout(), textLength, zoom.committedScale, contentTopPx) }
            .filterNotNull()
            .collect { hooks.onPosition(it) }
    }
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
