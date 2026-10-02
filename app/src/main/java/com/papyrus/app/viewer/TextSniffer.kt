package com.papyrus.app.viewer

import java.io.InputStream

/** Whether a file [DocumentFormat.detect] left unclassified is still worth showing as text; reads only the head. */
object TextSniffer {

    const val MAX_SNIFF_BYTES = 8 * 1024

    /** Share of control characters other than \n \r \t above which the bytes count as binary. */
    private const val MAX_CONTROL_RATIO = 0.02

    /** Checked before UTF-8 decoding because some binaries decode as valid UTF-8, a ZIP of ASCII names among them. */
    private val BINARY_MAGIC: List<Pair<String, ByteArray>> = listOf(
        "zip" to byteArrayOf(0x50, 0x4B, 0x03, 0x04), // PK.. also docx/odt/apk/jar
        "zip-empty" to byteArrayOf(0x50, 0x4B, 0x05, 0x06),
        "zip-spanned" to byteArrayOf(0x50, 0x4B, 0x07, 0x08),
        "pdf" to "%PDF".toByteArray(Charsets.US_ASCII),
        "png" to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
        "gif" to "GIF8".toByteArray(Charsets.US_ASCII),
        "jpeg" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
        "riff" to "RIFF".toByteArray(Charsets.US_ASCII),
        "gzip" to byteArrayOf(0x1F, 0x8B.toByte()),
        "elf" to byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()),
        "class" to byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()),
        "dex" to "dex\n".toByteArray(Charsets.US_ASCII),
        "sqlite" to "SQLite format 3".toByteArray(Charsets.US_ASCII),
        "wasm" to byteArrayOf(0x00, 0x61, 0x73, 0x6D),
        "rar" to byteArrayOf(0x52, 0x61, 0x72, 0x21),
        "7z" to byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte()),
        "bzip2" to "BZh".toByteArray(Charsets.US_ASCII),
        "xz" to byteArrayOf(0xFD.toByte(), '7'.code.toByte(), 'z'.code.toByte(), 'X'.code.toByte(), 'Z'.code.toByte(), 0x00),
    )

    fun looksLikeText(input: InputStream): Boolean {
        val head = ByteArray(MAX_SNIFF_BYTES)
        var total = 0
        while (total < head.size) {
            val n = input.read(head, total, head.size - total)
            if (n < 0) break
            total += n
        }
        return looksLikeText(head, total)
    }

    /** [length] is how many bytes of [head] are valid; the rest of the array is ignored. */
    fun looksLikeText(head: ByteArray, length: Int = head.size): Boolean {
        if (length <= 0) return false
        val bytes = if (length == head.size) head else head.copyOf(length)
        if (hasBinaryMagic(bytes)) return false
        if (bytes.any { it == 0.toByte() }) return false

        val text = try {
            // REPORT, not REPLACE: a throw on malformed input is the signal we want.
            Charsets.UTF_8.newDecoder().apply { onMalformedInput(java.nio.charset.CodingErrorAction.REPORT) }
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            return false
        }

        if (text.isEmpty()) return false
        val unexpected = text.count { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }
        return unexpected.toDouble() / text.length <= MAX_CONTROL_RATIO
    }

    private fun hasBinaryMagic(bytes: ByteArray): Boolean = BINARY_MAGIC.any { (_, magic) ->
        bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }
    }
}
