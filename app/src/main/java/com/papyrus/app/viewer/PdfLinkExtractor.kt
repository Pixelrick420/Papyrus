package com.papyrus.app.viewer

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import kotlin.coroutines.cancellation.CancellationException

/**
 * A PDF page's links, read on demand.
 *
 * A link is a link annotation: a rectangle plus either a URI action (open elsewhere) or a GoTo
 * destination (jump within the document). Any other action — JavaScript, launch, a named action —
 * is not something a reader can follow, so it is skipped. So is anything malformed: one bad
 * annotation must not cost the page its other links, and an unreadable page has no links rather
 * than an error. Nothing here throws.
 */
object PdfLinkExtractor {

    /** The links of page [index], in the same page-relative unit as find highlights. Empty on any failure. */
    fun extract(document: PDDocument, index: Int): List<PdfPageLink> {
        if (index !in 0 until document.numberOfPages) return emptyList()
        val links = ArrayList<PdfPageLink>()
        try {
            val page = document.getPage(index)
            val frame = PdfPageFrame.of(page)
            for (annotation in page.annotations) {
                try {
                    linkOf(annotation, frame, document)?.let { links.add(it) }
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

    /** [annotation] as a page link, or null when it is not a link, has no area, or goes nowhere we can follow. */
    private fun linkOf(annotation: PDAnnotation, frame: PdfPageFrame, document: PDDocument): PdfPageLink? {
        val link = annotation as? PDAnnotationLink ?: return null
        val rect = link.rectangle ?: return null
        val target = targetOf(link, document) ?: return null
        val norm = frame.toNorm(rect.lowerLeftX, rect.lowerLeftY, rect.upperRightX, rect.upperRightY) ?: return null
        return PdfPageLink(norm, target)
    }

    /**
     * Where [link] points. A URI action is filtered through [resolvedLink] so only http/https/mailto/tel
     * survive; a GoTo action is resolved to a page of [document]. A link with the destination directly on
     * the annotation and no action is read the same way.
     */
    private fun targetOf(link: PDAnnotationLink, document: PDDocument): PdfLinkTarget? {
        when (val action = link.action) {
            is PDActionURI -> return resolvedLink(action.uri)?.let { PdfLinkTarget.External(it) }
            is PDActionGoTo -> return pageOf(action.destination, document)
            null -> Unit
            else -> return null
        }
        return pageOf(link.destination, document)
    }

    /** The destination as a page index of [document], or null when it is not a real page destination. */
    private fun pageOf(destination: PDDestination?, document: PDDocument): PdfLinkTarget? {
        val page = destination as? PDPageDestination ?: return null
        // `page` is null when the destination carries a page number rather than a page object.
        val index = page.page?.let { document.pages.indexOf(it) } ?: page.pageNumber
        return if (index in 0 until document.numberOfPages) PdfLinkTarget.Internal(index) else null
    }
}
