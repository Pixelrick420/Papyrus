package io.github.pixelrick420.papyrus.viewer

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

internal enum class PdfOpenFallback { COPY, FAIL_FORMAT, FAIL_NO_SPACE }

internal const val MAX_PDF_COPY_BYTES = 512L * 1024 * 1024

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
    usableSpace <= 0 -> PdfOpenFallback.FAIL_NO_SPACE
    statSize >= 0 && statSize > MAX_PDF_COPY_BYTES -> PdfOpenFallback.FAIL_NO_SPACE
    statSize >= 0 && usableSpace < statSize -> PdfOpenFallback.FAIL_NO_SPACE
    else -> PdfOpenFallback.COPY
}

@Throws(IOException::class)
internal fun copyBounded(input: InputStream, output: OutputStream, limit: Long) {
    val buffer = ByteArray(COPY_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        copied += read
        if (copied > limit) throw IOException("PDF copy exceeds the size limit")
        output.write(buffer, 0, read)
    }
}

private const val COPY_BUFFER_SIZE = 64 * 1024
