package com.papyrus.app.ui.viewer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.view.MotionEvent
import android.widget.TextView
import androidx.compose.runtime.staticCompositionLocalOf
import com.papyrus.app.viewer.BookLink
import com.papyrus.app.viewer.resolvedLink
import io.noties.markwon.core.spans.LinkSpan

/**
 * How a document surface asks for a link to be opened. A viewer provides one so every surface in it
 * reports an unhandled link the same way; the default does nothing.
 */
internal val LocalLinkOpener = staticCompositionLocalOf<(String) -> Unit> { { _ -> } }

/**
 * The same for a link to a place inside the open book, which the viewer scrolls to itself and never hands
 * to the system; the default does nothing, so a surface outside a viewer keeps working.
 */
internal val LocalBookLinkOpener = staticCompositionLocalOf<(BookLink) -> Unit> { { _ -> } }

/**
 * Opens [url] in whichever app handles it, `false` when none did. Filtered through [resolvedLink] first
 * so a document can never send the reader to a `javascript:`, `file:` or `intent:` address.
 */
internal fun openLink(context: Context, url: String): Boolean {
    val resolved = resolvedLink(url) ?: return false
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(resolved))
    // An application context needs its own task to start an Activity; an Activity itself does not.
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(intent)
        true
    } catch (ignored: Exception) {
        false
    }
}

/**
 * A [LinkMovementMethod] that hands a tapped link to [onLink] instead of the span's own resolver,
 * which catches only `ActivityNotFoundException` and so would crash on a `file://` tap.
 */
internal class GuardedLinkMovementMethod(private val onLink: (String) -> Unit) : LinkMovementMethod() {

    override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val link = linkUnder(widget, buffer, event.x, event.y)
            if (link != null) {
                onLink(link)
                return true
            }
        }
        return super.onTouchEvent(widget, buffer, event)
    }

    private fun linkUnder(widget: TextView, buffer: Spannable, x: Float, y: Float): String? {
        val layout = widget.layout ?: return null
        val column = x.toInt() - widget.totalPaddingLeft + widget.scrollX
        val row = y.toInt() - widget.totalPaddingTop + widget.scrollY
        val line = layout.getLineForVertical(row)
        // Outside the line's extent, so a touch on blank space beside it is not a link tap.
        if (column < layout.getLineLeft(line) || column > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, column.toFloat())
        // A touch at the right edge of a link resolves to the offset just past it.
        return linkAt(buffer, offset) ?: linkAt(buffer, (offset - 1).coerceAtLeast(0))
    }

    private fun linkAt(buffer: Spannable, offset: Int): String? =
        buffer.getSpans(offset, offset, LinkSpan::class.java).firstOrNull()?.link
}
