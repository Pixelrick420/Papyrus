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
import com.papyrus.app.viewer.resolvedLink
import io.noties.markwon.core.spans.LinkSpan

/**
 * How a document surface asks for a link to be opened. A viewer screen provides one so every surface
 * in it reports a link that nothing could handle the same way; the default does nothing.
 */
internal val LocalLinkOpener = staticCompositionLocalOf<(String) -> Unit> { { _ -> } }

/**
 * Opens [url] in whichever app handles it, and says whether one took it. The URL is filtered first
 * ([resolvedLink]) so a document can never send the reader to a `javascript:`, `file:` or `intent:`
 * address. Every failure is reported as `false` — no app for the scheme, a provider that refuses it,
 * anything else — because the caller shows a message and a viewer must not crash on a bad link.
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
 * A [LinkMovementMethod] that hands a tapped link to [onLink] instead of letting the span's own
 * resolver run.
 *
 * Markwon's default resolver catches only `ActivityNotFoundException`, so tapping a `file://` link
 * in a Markdown document would surface `FileUriExposedException` and crash. Intercepting the tap
 * here means the resolver is never reached, and the scheme filter and the failure report stay in
 * [onLink] with every other link path. Everything that is not a [LinkSpan] tap is passed to the
 * superclass unchanged, so long-press selection keeps working.
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

    /** The destination of the link under a touch, or null when the touch is not on one. */
    private fun linkUnder(widget: TextView, buffer: Spannable, x: Float, y: Float): String? {
        val layout = widget.layout ?: return null
        val column = x.toInt() - widget.totalPaddingLeft + widget.scrollX
        val row = y.toInt() - widget.totalPaddingTop + widget.scrollY
        val line = layout.getLineForVertical(row)
        // Outside the line's own extent, so a touch on blank space beside it is not a link tap.
        if (column < layout.getLineLeft(line) || column > layout.getLineRight(line)) return null
        val offset = layout.getOffsetForHorizontal(line, column.toFloat())
        // A touch at the right edge of a link resolves to the offset just past it.
        return linkAt(buffer, offset) ?: linkAt(buffer, (offset - 1).coerceAtLeast(0))
    }

    private fun linkAt(buffer: Spannable, offset: Int): String? =
        buffer.getSpans(offset, offset, LinkSpan::class.java).firstOrNull()?.link
}
