package io.github.pixelrick420.papyrus.viewer

data class PdfPageLink(val rect: NormRect, val target: PdfLinkTarget)

sealed interface PdfLinkTarget {

    data class External(val url: String) : PdfLinkTarget

    data class Internal(val page: Int) : PdfLinkTarget
}
