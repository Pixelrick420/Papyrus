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
 * A PDF page's links, read on demand: a rectangle plus either a URI action (open elsewhere) or a GoTo
 * destination. Unfollowable or malformed annotations are skipped, and nothing here throws.
 */
object PdfLinkExtractor {

    /** Page [index]'s links, in the same unit as find highlights; empty on any failure. */
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

    /** [annotation] as a page link, or null when it is not a link, has no area, or goes nowhere. */
    private fun linkOf(annotation: PDAnnotation, frame: PdfPageFrame, document: PDDocument): PdfPageLink? {
        val link = annotation as? PDAnnotationLink ?: return null
        val rect = link.rectangle ?: return null
        val target = targetOf(link, document) ?: return null
        val norm = frame.toNorm(rect.lowerLeftX, rect.lowerLeftY, rect.upperRightX, rect.upperRightY) ?: return null
        return PdfPageLink(norm, target)
    }

    /**
     * Where [link] points: a URI action filtered through [resolvedLink] so only http/https/mailto/tel
     * survive, or a GoTo destination resolved to a page of [document].
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

    private fun pageOf(destination: PDDestination?, document: PDDocument): PdfLinkTarget? {
        val page = destination as? PDPageDestination ?: return null
        // `page` is null when the destination carries a page number rather than a page object.
        val index = page.page?.let { document.pages.indexOf(it) } ?: page.pageNumber
        return if (index in 0 until document.numberOfPages) PdfLinkTarget.Internal(index) else null
    }
}
