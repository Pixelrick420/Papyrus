package com.papyrus.app.viewer

/** One link on a rendered PDF page: where it is ([rect], page-relative as find highlights) and where it goes. */
data class PdfPageLink(val rect: NormRect, val target: PdfLinkTarget)

/** Where a tapped PDF link goes. */
sealed interface PdfLinkTarget {

    /** A URI action, already filtered to http/https/mailto/tel. */
    data class External(val url: String) : PdfLinkTarget

    /** A GoTo destination, resolved to a 0-based page index of the open document. */
    data class Internal(val page: Int) : PdfLinkTarget
}
