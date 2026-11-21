package io.github.pixelrick420.papyrus.viewer

internal enum class PdfOpenFallback { COPY, FAIL_FORMAT, FAIL_NO_SPACE }

private val PDF_HEADER = "%PDF-".toByteArray(Charsets.US_ASCII)

internal fun startsWithPdfHeader(head: ByteArray): Boolean =
    head.size >= PDF_HEADER.size && PDF_HEADER.indices.all { head[it] == PDF_HEADER[it] }

internal fun pdfOpenFallback(
    statSize: Long,
    seekable: Boolean,
    hasPdfHeader: Boolean,
    usableSpace: Long,
): PdfOpenFallback = when {
    !hasPdfHeader -> PdfOpenFallback.FAIL_FORMAT
    statSize >= 0 && seekable -> PdfOpenFallback.FAIL_FORMAT
    statSize >= 0 && usableSpace < statSize -> PdfOpenFallback.FAIL_NO_SPACE
    else -> PdfOpenFallback.COPY
}
