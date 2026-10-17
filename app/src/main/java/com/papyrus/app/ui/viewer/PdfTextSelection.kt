package com.papyrus.app.ui.viewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.papyrus.app.R
import com.papyrus.app.viewer.NormRect
import com.papyrus.app.viewer.PagePoint
import com.papyrus.app.viewer.PdfLinkTarget
import com.papyrus.app.viewer.PdfPageGeometry
import com.papyrus.app.viewer.PdfPageLink
import com.papyrus.app.viewer.PdfPageText
import com.papyrus.app.viewer.PdfSelection
import com.papyrus.app.viewer.PdfTextPos
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/*
 * A PDF page is a bitmap with no selectable text, so its extracted characters are hit-tested by
 * hand. All units are page-relative, so a selection survives zoom and can run across pages.
 */

internal val SelectionReach = 24.dp

internal val HandleRadius = 8.dp

/** Grab radius is far bigger than the knob: it stands for a fingertip. */
internal val HandleGrabRadius = 28.dp

private val HandleStem = 2.dp

/**
 * What is selected in one PDF. A page's text is read on first point, or when a page scrolls into a
 * selection. Each gesture report restarts the work: only the newest position matters.
 */
@Stable
internal class PdfSelectionState(
    private val scope: CoroutineScope,
    private val loadText: suspend (Int) -> PdfPageText?,
    private val onNoText: () -> Unit,
) {
    var selection by mutableStateOf<PdfSelection?>(null)
        private set

    val hasSelection: Boolean get() = selection != null

    /** A state map, so a page drawing the selection redraws when its text arrives. */
    private val texts = mutableStateMapOf<Int, PdfPageText>()

    private var startJob: Job? = null
    private var dragJob: Job? = null

    /** The word the long-press landed on: what a drag extends from, in either direction. */
    private var anchorWord: Pair<PdfTextPos, PdfTextPos>? = null

    private var fixedEnd: PdfTextPos? = null

    fun textOf(page: Int): PdfPageText? = texts[page]

    suspend fun ensureText(page: Int): PdfPageText? {
        texts[page]?.let { return it }
        val loaded = loadText(page) ?: return null
        texts[page] = loaded
        return loaded
    }

    /** A long-press at [point]: selects the word there. [reach] is in page heights, not pixels. */
    fun startAt(point: PagePoint, reach: Float) {
        cancelWork()
        anchorWord = null
        fixedEnd = null
        startJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val text = ensureText(point.page)
            if (text == null || !text.isSelectable) {
                // A scan has no text at all, which is worth telling the reader.
                onNoText()
                return@launch
            }
            val hit = text.nearestChar(point.x, point.y, point.aspect)
            val word = hit?.takeIf { it.distance <= reach }?.let { text.wordAround(it.index) } ?: return@launch
            val span = PdfTextPos(point.page, word.first) to PdfTextPos(point.page, word.last + 1)
            anchorWord = span
            selection = PdfSelection.between(span.first, span.second)
        }
    }

    fun dragTo(point: PagePoint) {
        dragJob?.cancel()
        dragJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            startJob?.join()
            val word = anchorWord ?: return@launch
            val caret = caretAt(point) ?: return@launch
            PdfSelection.between(minOf(word.first, caret), maxOf(word.second, caret))?.let { selection = it }
        }
    }

    fun startHandle(isStart: Boolean) {
        val current = selection ?: return
        cancelWork()
        anchorWord = null
        fixedEnd = if (isStart) current.end else current.start
    }

    /** Passing the other end makes the two swap roles, as they do on a phone. */
    fun dragHandle(point: PagePoint) {
        val fixed = fixedEnd ?: return
        dragJob?.cancel()
        dragJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val caret = caretAt(point) ?: return@launch
            // Null when the handle is back on the fixed end, which would select nothing: keep what there is.
            PdfSelection.between(fixed, caret)?.let { selection = it }
        }
    }

    fun finishGesture() {
        anchorWord = null
        fixedEnd = null
    }

    fun clear() {
        cancelWork()
        anchorWord = null
        fixedEnd = null
        selection = null
    }

    /** Selected text, pages joined by newlines, loading any page not yet read. Null if none. */
    suspend fun selectedText(): String? {
        val current = selection ?: return null
        val out = StringBuilder()
        for (page in current.pages) {
            val span = current.spanOn(page) ?: continue
            val text = ensureText(page) ?: continue
            val part = text.textBetween(span.from, span.to).trim()
            if (part.isEmpty()) continue
            if (out.isNotEmpty()) out.append('\n')
            out.append(part)
        }
        return out.toString().ifEmpty { null }
    }

    private suspend fun caretAt(point: PagePoint): PdfTextPos? {
        val text = ensureText(point.page) ?: return null
        return text.caretAt(point.x, point.y, point.aspect)?.let { PdfTextPos(point.page, it) }
    }

    private fun cancelWork() {
        startJob?.cancel()
        dragJob?.cancel()
    }
}

/** A selection end's knob position in that page's pixels. */
internal class HandleSpot(val x: Float, val top: Float, val bottom: Float, private val radius: Float) {
    /** Below the line, so the finger holding it does not cover the text. */
    val knob: Offset get() = Offset(x, bottom + radius)

    /** The line's middle: what a caret is worked out from while it is dragged. */
    val line: Offset get() = Offset(x, (top + bottom) / 2f)
}

/** The knob taken, and the line's offset from the finger, to keep while it is dragged. */
internal class HandleGrab(val isStart: Boolean, val toLine: Offset)

internal class PageHandles(val start: HandleSpot?, val end: HandleSpot?) {

    fun grab(point: Offset, radius: Float): HandleGrab? {
        val s = start
        val e = end
        val toStart = if (s != null) (s.knob - point).getDistance() else Float.MAX_VALUE
        val toEnd = if (e != null) (e.knob - point).getDistance() else Float.MAX_VALUE
        if (s != null && toStart <= radius && toStart <= toEnd) return HandleGrab(true, s.line - point)
        if (e != null && toEnd <= radius) return HandleGrab(false, e.line - point)
        return null
    }

    companion object {
        val None = PageHandles(null, null)
    }
}

internal fun pageHandles(
    selection: PdfSelection?,
    page: Int,
    rects: List<NormRect>,
    size: IntSize,
    radius: Float,
): PageHandles {
    if (selection == null || rects.isEmpty() || size == IntSize.Zero) return PageHandles.None
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    val first = rects.first()
    val last = rects.last()
    return PageHandles(
        start = if (selection.start.page == page) HandleSpot(first.left * w, first.top * h, first.bottom * h, radius) else null,
        end = if (selection.end.page == page) HandleSpot(last.right * w, last.top * h, last.bottom * h, radius) else null,
    )
}

/** A faint wash plus an underline on each link rectangle, under the find highlights and the selection. */
internal fun DrawScope.drawLinks(rects: List<NormRect>, color: Color) {
    for (rect in rects) {
        val left = rect.left * size.width
        val top = rect.top * size.height
        val width = (rect.right - rect.left) * size.width
        val height = (rect.bottom - rect.top) * size.height
        drawRect(
            color = color.copy(alpha = LINK_FILL_ALPHA),
            topLeft = Offset(left, top),
            size = Size(width, height),
        )
        drawLine(
            color = color.copy(alpha = LINK_LINE_ALPHA),
            start = Offset(left, top + height),
            end = Offset(left + width, top + height),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

internal fun DrawScope.drawSelection(rects: List<NormRect>, handles: PageHandles, color: Color, radius: Float) {
    for (rect in rects) {
        drawRect(
            color = color.copy(alpha = SELECTION_ALPHA),
            topLeft = Offset(rect.left * size.width, rect.top * size.height),
            size = Size((rect.right - rect.left) * size.width, (rect.bottom - rect.top) * size.height),
        )
    }
    for (spot in listOfNotNull(handles.start, handles.end)) {
        drawLine(color, Offset(spot.x, spot.top), Offset(spot.x, spot.bottom), strokeWidth = HandleStem.toPx())
        drawCircle(color, radius, spot.knob)
    }
}

private const val SELECTION_ALPHA = 0.3f

/** Light enough not to hide the page under it. */
private const val LINK_FILL_ALPHA = 0.12f
private const val LINK_LINE_ALPHA = 0.6f

/** Link hit slop, in page pixels. */
internal val LinkTapSlop = 4.dp

/**
 * What a press turned out to be. A tap carries its release so a link tap can consume it — that is
 * what keeps the double-tap zoom above from also firing under a tap that opened a link.
 */
private sealed interface Press {
    class Tap(val up: PointerInputChange) : Press
    data object Long : Press
    data object Other : Press
}

/**
 * Long-press selects, drag extends, tap dismisses or follows a link, a knob moves an end. Nothing
 * is claimed until the touch is known, so scroll, pinch-zoom and double-tap zoom are unaffected.
 */
internal fun Modifier.pdfSelectionGestures(
    state: PdfSelectionState,
    page: Int,
    geometry: State<PdfPageGeometry>,
    handles: State<PageHandles>,
    links: State<List<PdfPageLink>>,
    pageSize: State<IntSize>,
    onLink: (PdfLinkTarget) -> Unit,
    haptics: HapticFeedback,
    reachPx: Float,
    grabRadiusPx: Float,
    linkSlopPx: Float,
): Modifier = pointerInput(state, page, reachPx, grabRadiusPx, linkSlopPx) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val grabbed = handles.value.grab(down.position, grabRadiusPx)
        if (grabbed != null) {
            down.consume()
            state.startHandle(grabbed.isStart)
            trackDrag(down.id) { position ->
                val target = position + grabbed.toLine
                state.dragHandle(geometry.value.locate(page, target.x, target.y))
            }
            state.finishGesture()
            return@awaitEachGesture
        }
        when (val press = classifyPress(down)) {
            is Press.Tap -> {
                val target = linkAt(links.value, pageSize.value, down.position, linkSlopPx)
                if (target != null) {
                    press.up.consume()
                    onLink(target)
                } else {
                    state.clear()
                }
            }
            Press.Other -> Unit
            Press.Long -> {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                val where = geometry.value
                val reach = reachPx / where.heightOf(page)
                state.startAt(where.locate(page, down.position.x, down.position.y), reach)
                trackDrag(down.id) { position ->
                    state.dragTo(geometry.value.locate(page, position.x, position.y))
                }
                state.finishGesture()
            }
        }
    }
}

private fun linkAt(links: List<PdfPageLink>, size: IntSize, point: Offset, slopPx: Float): PdfLinkTarget? {
    if (links.isEmpty() || size == IntSize.Zero) return null
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    for (link in links) {
        val rect = link.rect
        val left = rect.left * w - slopPx
        val top = rect.top * h - slopPx
        val right = rect.right * w + slopPx
        val bottom = rect.bottom * h + slopPx
        if (point.x in left..right && point.y in top..bottom) return link.target
    }
    return null
}

/** Classifies a press: held still, let go early, or something else. */
private suspend fun AwaitPointerEventScope.classifyPress(down: PointerInputChange): Press {
    var verdict: Press? = null
    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        while (verdict == null) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            verdict = when {
                // Consumed means a pinch above has taken it; a second finger is not ours either.
                change == null || change.isConsumed || event.changes.size > 1 -> Press.Other
                !change.pressed -> Press.Tap(change)
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop -> Press.Other
                else -> null
            }
        }
    }
    return verdict ?: Press.Long
}

/** Follows [id] until it lifts, consuming moves so the list cannot scroll under a drag. */
private suspend fun AwaitPointerEventScope.trackDrag(id: PointerId, onMove: (Offset) -> Unit) {
    var tracking = true
    while (tracking) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == id }
        if (change == null || change.isConsumed) {
            tracking = false
        } else {
            change.consume()
            if (change.pressed) onMove(change.position) else tracking = false
        }
    }
}

/** Android 13+ shows its own copy confirmation, so a second toast would stack on it. */
internal fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val copied = try {
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), text))
        true
    } catch (ignored: RuntimeException) {
        // A very long selection overflows the binder transaction that carries the clip.
        false
    }
    when {
        !copied -> Toast.makeText(context, R.string.viewer_copy_failed, Toast.LENGTH_SHORT).show()
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ->
            Toast.makeText(context, R.string.viewer_copied, Toast.LENGTH_SHORT).show()
    }
}
