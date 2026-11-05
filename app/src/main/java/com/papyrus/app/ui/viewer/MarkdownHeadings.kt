package com.papyrus.app.ui.viewer

import android.graphics.Rect
import android.os.Bundle
import android.text.Spanned
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import io.noties.markwon.core.spans.HeadingSpan

/**
 * Markdown is one TextView, so its headings are spans TalkBack cannot see. This gives each heading a
 * virtual child marked as a heading, which is what TalkBack's heading navigation steps through.
 */
internal fun TextView.exposeMarkdownHeadings(): ExploreByTouchHelper =
    MarkdownHeadings(this).also { ViewCompat.setAccessibilityDelegate(this, it) }

/** Re-reads the headings after the view's text was replaced. */
internal fun TextView.refreshMarkdownHeadings() {
    (ViewCompat.getAccessibilityDelegate(this) as? ExploreByTouchHelper)?.invalidateRoot()
}

private class MarkdownHeadings(private val host: TextView) : ExploreByTouchHelper(host) {

    private fun headings(): List<IntRange> {
        val text = host.text as? Spanned ?: return emptyList()
        return text.getSpans(0, text.length, HeadingSpan::class.java)
            .map { text.getSpanStart(it) until text.getSpanEnd(it) }
            .filter { !it.isEmpty() && text.subSequence(it.first, it.last + 1).isNotBlank() }
            .sortedBy { it.first }
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
        headings().forEachIndexed { index, range ->
            if (bounds(range)?.contains(x.toInt(), y.toInt()) == true) return index
        }
        return INVALID_ID
    }

    override fun getVisibleVirtualViews(virtualViewIds: MutableList<Int>) {
        headings().indices.forEach { virtualViewIds.add(it) }
    }

    override fun onPopulateNodeForVirtualView(virtualViewId: Int, node: AccessibilityNodeInfoCompat) {
        val range = headings().getOrNull(virtualViewId)
        val rect = range?.let { bounds(it) }
        if (range == null || rect == null) {
            // A stale id still needs text and bounds, or the helper throws.
            node.text = " "
            node.setBoundsInParent(Rect(0, 0, 1, 1))
            return
        }
        node.text = host.text.subSequence(range.first, range.last + 1).toString().trim()
        node.isHeading = true
        node.setBoundsInParent(rect)
    }

    override fun onPerformActionForVirtualView(virtualViewId: Int, action: Int, arguments: Bundle?): Boolean = false
}
