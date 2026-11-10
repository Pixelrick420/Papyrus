package com.papyrus.app.viewer

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.coroutines.cancellation.CancellationException

// A PDF's bookmarks as the flat, depth-tagged list the outline sheet shows. The outline is author-supplied, so every walk is bounded:
// each bookmark is visited once (cycles), [MAX_ENTRIES] and [MAX_LEVELS] cap it, and one bad bookmark never costs its siblings.
object PdfOutlineReader {

    /** Bookmarks visited before giving up; a real book has a few thousand at the very most. */
    const val MAX_ENTRIES = 5_000

    /** Levels descended; real outlines stop at four or five, and recursion has to stay shallow. */
    const val MAX_LEVELS = 32

    /** Top-level bookmarks read before a progressive read hands over what it has. */
    private const val EMIT_BATCH = 64

    // Empty when the document has none or it cannot be read. [emit] gets everything read so far after each top-level bookmark,
    // so a long outline shows while the rest is still being read.
    fun read(document: PDDocument, emit: (List<OutlineEntry>) -> Unit = {}): List<OutlineEntry> {
        val outline = attempt { document.documentCatalog.documentOutline } ?: return emptyList()
        return Walk(Destinations(document))
            .siblings(attempt { outline.firstChild }, level = 0, depth = 0, emit = emit)
    }

    private class Walk(private val destinations: Destinations) {
        /** Identity, not equality: two distinct bookmarks may be equal dictionaries, and a loop revisits the same one. */
        private val visited: MutableSet<COSDictionary> = Collections.newSetFromMap(IdentityHashMap())
        private var budget = MAX_ENTRIES

        // The entries of the sibling chain at [first], each followed by its subtree. [level] bounds the recursion;
        // [depth] is the indent, which a skipped untitled bookmark does not add to.
        fun siblings(
            first: PDOutlineItem?,
            level: Int,
            depth: Int,
            emit: ((List<OutlineEntry>) -> Unit)? = null,
        ): List<OutlineEntry> {
            val out = ArrayList<OutlineEntry>()
            // What emit has already been given. Only a top-level list is emitted, and it only ever grows
            // by appending, so a snapshot is always a prefix of what [siblings] will return.
            var emitted = 0
            var item = first
            while (item != null && budget > 0) {
                val dictionary = item.cosObject
                if (!visited.add(dictionary)) break // the chain came back to a bookmark already read
                budget--
                val current = item
                val title = cleanOutlineTitle(attempt { current.title }.orEmpty())
                val target = destinations.pageOf(current)
                val untitled = title.isEmpty()
                val below = if (level + 1 < MAX_LEVELS) {
                    attempt { siblings(current.firstChild, level + 1, if (untitled) depth else depth + 1) }.orEmpty()
                } else {
                    emptyList()
                }
                when {
                    untitled -> out.addAll(below)
                    target != null || below.isNotEmpty() -> {
                        out.add(OutlineEntry(target ?: NO_OUTLINE_TARGET, depth, title))
                        out.addAll(below)
                    }
                }
                if (emit != null && out.size - emitted >= EMIT_BATCH) {
                    emit(ArrayList(out))
                    emitted = out.size
                }
                item = attempt { current.nextSibling }
            }
            if (emit != null && out.size > emitted) emit(ArrayList(out))
            return out
        }
    }

    /** Turns a bookmark's destination into a page index of one document. */
    private class Destinations(private val document: PDDocument) {
        private val pageCount = attempt { document.numberOfPages } ?: 0

        // Each page's index by its dictionary, built once on first use. [com.tom_roush.pdfbox.pdmodel.PDPageTree.indexOf]
        // walks the page tree on every call, so doing that per bookmark is quadratic on a big book.
        private val indexByPage: Map<COSDictionary, Int> by lazy {
            val map = IdentityHashMap<COSDictionary, Int>()
            attempt { document.pages.forEachIndexed { index, page -> map[page.cosObject] = index } }
            map
        }

        /** The page [item] goes to, or null: no destination, one that is not a page, or a page that is not there. */
        fun pageOf(item: PDOutlineItem): Int? {
            // The item's own destination first, then a GoTo action's; each read on its own, so one
            // that throws does not hide the other.
            attempt { item.destination }?.let { dest -> indexOf(dest)?.let { return it } }
            val action = attempt { item.action } as? PDActionGoTo ?: return null
            return attempt { action.destination }?.let { indexOf(it) }
        }

        private fun indexOf(destination: PDDestination): Int? = when (destination) {
            is PDPageDestination -> indexOf(destination)
            // One hop only: a name that resolves to another name is malformed, not something to chase.
            is PDNamedDestination -> named(destination.namedDestination)?.let { indexOf(it) }
            else -> null
        }

        private fun indexOf(destination: PDPageDestination): Int? {
            // `page` is null when the destination carries a page number instead of a page object. A page
            // object that is not in the tree is unresolved: its stale number is not a fallback.
            val pageObject = attempt { destination.page }
            val index = if (pageObject != null) indexByPage[pageObject.cosObject] else attempt { destination.pageNumber }
            return index?.takeIf { it in 0 until pageCount }
        }

        /** A named destination, from the name tree (PDF 1.2 and later) and then the catalog's old /Dests dictionary. */
        private fun named(name: String?): PDPageDestination? {
            if (name.isNullOrEmpty()) return null
            val catalog = attempt { document.documentCatalog } ?: return null
            attempt { catalog.names?.dests?.getValue(name) }?.let { return it }
            return attempt { catalog.dests?.getDestination(name) } as? PDPageDestination
        }
    }

    /** [block]'s result, or null if it threw: PdfBox throws unchecked on some malformed files, not only IOException. */
    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (ignored: Exception) {
        null
    }
}
