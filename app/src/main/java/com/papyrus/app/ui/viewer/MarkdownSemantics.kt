package com.papyrus.app.ui.viewer

import android.graphics.Rect
import android.os.Bundle
import android.text.Spanned
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import io.noties.markwon.core.spans.HeadingSpan
import io.noties.markwon.image.AsyncDrawableSpan

// Markdown is one TextView, so TalkBack cannot see its heading and image spans: each heading gets a virtual child marked as a heading, and each image with alt text one that reads it.
internal fun TextView.exposeMarkdownSemantics(): ExploreByTouchHelper =
    MarkdownSemantics(this).also { ViewCompat.setAccessibilityDelegate(this, it) }

internal fun TextView.refreshMarkdownSemantics() {
    (ViewCompat.getAccessibilityDelegate(this) as? ExploreByTouchHelper)?.invalidateRoot()
}

/** What Markwon leaves in the text for an image that names no alt text, so that the span has a character to cover. */
private const val OBJECT_REPLACEMENT = "\uFFFC"

private class MarkdownSemantics(private val host: TextView) : ExploreByTouchHelper(host) {

    private class Item(val range: IntRange, val label: String, val isImage: Boolean)

    private fun items(): List<Item> {
        val text = host.text as? Spanned ?: return emptyList()
        val headings = text.getSpans(0, text.length, HeadingSpan::class.java)
            .map { text.getSpanStart(it) until text.getSpanEnd(it) }
            .filter { !it.isEmpty() && text.subSequence(it.first, it.last + 1).isNotBlank() }
            .map { Item(it, text.subSequence(it.first, it.last + 1).toString().trim(), isImage = false) }
        // Markwon renders an image over its alt text, which stays in the text under the span.
        val images = text.getSpans(0, text.length, AsyncDrawableSpan::class.java).mapNotNull { span ->
            val range = text.getSpanStart(span) until text.getSpanEnd(span)
            if (range.isEmpty()) return@mapNotNull null
            val alt = text.subSequence(range.first, range.last + 1).toString().replace(OBJECT_REPLACEMENT, "").trim()
            if (alt.isEmpty()) null else Item(range, alt, isImage = true)
        }
        return (headings + images).sortedBy { it.range.first }
    }

    private fun bounds(range: IntRange): Rect? {
        val layout = host.layout ?: return null
        val last = layout.text.length - 1
        if (last < 0) return null
        val first = layout.getLineForOffset(range.first.coerceIn(0, last))
        val end = layout.getLineForOffset(range.last.coerceIn(0, last))
        val top = host.totalPaddingTop
        return Rect(
            host.totalPaddingLeft,
            top + layout.getLineTop(first),
            host.width - host.totalPaddingRight,
            top + layout.getLineBottom(end),
        )
    }

    override fun getVirtualViewAt(x: Float, y: Float): Int {
        // The narrowest item wins, so an image inside a heading is found before the heading around it.
        var hit = INVALID_ID
        var hitLength = Int.MAX_VALUE
        items().forEachIndexed { index, item ->
            val length = item.range.last - item.range.first
            if (length < hitLength && bounds(item.range)?.contains(x.toInt(), y.toInt()) == true) {
                hit = index
                hitLength = length
            }
        }
        return hit
    }

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
        items().indices.forEach { virtualViewIds.add(it) }
    }

    override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
        val item = items().getOrNull(virtualViewId)
        val rect = item?.let { bounds(it.range) }
        if (item == null || rect == null) {
            // A stale id still needs text and bounds, or the helper throws.
            node.text = " "
            setBoundsInScreenFromBoundsInParent(node, Rect(0, 0, 1, 1))
            return
        }
        if (item.isImage) {
            // An image is described rather than read: TalkBack announces it as an image with this text.
            node.className = ImageView::class.java.name
            node.contentDescription = item.label
        } else {
            node.text = item.label
            node.isHeading = true
        }
        setBoundsInScreenFromBoundsInParent(node, rect)
    }

    override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean = false
}
