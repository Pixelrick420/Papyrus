package com.papyrus.app.viewer

// The tree is implicit: an entry has children when the next one is deeper; these functions fold and unfold it by position in the flat list.

internal const val AUTO_FOLD_ABOVE = 60

fun hasChildren(entries: List<OutlineEntry>, position: Int): Boolean =
    position in 0 until entries.lastIndex && entries[position + 1].depth > entries[position].depth

// A folded entry hidden by an outer fold changes nothing, and a position in [folded] that is not a parent is ignored.
fun visibleOutline(entries: List<OutlineEntry>, folded: Set<Int>): List<Int> {
    val shown = ArrayList<Int>(entries.size)
    var hiddenBelow: Int? = null
    for (position in entries.indices) {
        val depth = entries[position].depth
        if (hiddenBelow != null) {
            if (depth > hiddenBelow) continue
            hiddenBelow = null
        }
        shown.add(position)
        if (position in folded && hasChildren(entries, position)) hiddenBelow = depth
    }
    return shown
}

fun initialFolds(entries: List<OutlineEntry>): Set<Int> =
    if (entries.size <= AUTO_FOLD_ABOVE) emptySet() else entries.indices.filter { hasChildren(entries, it) }.toSet()
