package io.github.pixelrick420.papyrus.viewer

import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import java.util.IdentityHashMap
import kotlin.coroutines.cancellation.CancellationException

object PdfLinkExtractor {

    fun extract(document: PDDocument, index: Int): List<PdfPageLink> {
        if (index !in 0 until document.numberOfPages) return emptyList()
        val links = ArrayList<PdfPageLink>()
        try {
            val page = document.getPage(index)
            val frame = PdfPageFrame.of(page)
            val destinations = Destinations(document)
            for (annotation in page.annotations) {
                try {
                    linkOf(annotation, frame, destinations)?.let { links.add(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (ignored: Exception) {
                    // One annotation PdfBox chokes on: skip it, keep the page's other links.
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (ignored: Exception) {
            // A page whose annotation tree cannot be read has no links at all.
        }
        return links
    }

    private fun linkOf(annotation: PDAnnotation, frame: PdfPageFrame, destinations: Destinations): PdfPageLink? {
        val link = annotation as? PDAnnotationLink ?: return null
        val rect = link.rectangle ?: return null
        val target = targetOf(link, destinations) ?: return null
        val norm = frame.toNorm(rect.lowerLeftX, rect.lowerLeftY, rect.upperRightX, rect.upperRightY) ?: return null
        return PdfPageLink(norm, target)
    }

    private fun targetOf(link: PDAnnotationLink, destinations: Destinations): PdfLinkTarget? {
        when (val action = link.action) {
            is PDActionURI -> return resolvedLink(action.uri)?.let { PdfLinkTarget.External(it) }
            is PDActionGoTo -> return destinations.targetOf(action.destination)
            null -> Unit
            else -> return null
        }
        return destinations.targetOf(link.destination)
    }

    /** Turns a link's destination into a page index of one document. */
    private class Destinations(private val document: PDDocument) {
        private val pageCount = document.numberOfPages

        // Built on first use: PDPageTree.indexOf walks the page tree, so a lookup per link is quadratic.
        private val indexByPage: Map<COSDictionary, Int> by lazy {
            val map = IdentityHashMap<COSDictionary, Int>()
            document.pages.forEachIndexed { index, page -> map[page.cosObject] = index }
            map
        }

        fun targetOf(destination: PDDestination?): PdfLinkTarget? {
            val index = when (destination) {
                is PDPageDestination -> indexOf(destination)
                // One hop only: a name that resolves to another name is malformed, not something to chase.
                is PDNamedDestination -> named(destination.namedDestination)?.let { indexOf(it) }
                else -> null
            } ?: return null
            return if (index in 0 until pageCount) PdfLinkTarget.Internal(index) else null
        }

        private fun indexOf(destination: PDPageDestination): Int? {
            // `page` is null when the destination carries a page number rather than a page object.
            val page = destination.page
            return if (page != null) indexByPage[page.cosObject] else destination.pageNumber
        }

        /** A named destination, from the name tree (PDF 1.2 and later) and then the catalog's old /Dests dictionary. */
        private fun named(name: String?): PDPageDestination? {
            if (name.isNullOrEmpty()) return null
            val catalog = document.documentCatalog
            catalog.names?.dests?.getValue(name)?.let { return it }
            return catalog.dests?.getDestination(name) as? PDPageDestination
        }
    }
}
