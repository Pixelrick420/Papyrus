package io.github.pixelrick420.papyrus.viewer.pptx.text

internal data class EotContainer(
    val familyName: String,
    val styleName: String,
    val fullName: String,
    val fontData: ByteArray,
    val compressed: Boolean,
)

/** Reads the Embedded OpenType wrapper PowerPoint writes as `.fntdata`. */
internal object EotReader {

    private const val EOT_MAGIC = 0x504C

    private const val TT_COMPRESSED = 0x00000004

    private const val XOR_ENCRYPTED = 0x10000000

    private const val XOR_KEY = 0x50

    fun open(bytes: ByteArray): EotContainer? {
        if (bytes.size < 12) return null
        val version = when (le32(bytes, 8)) {
            0x00010000L -> 1
            0x00020001L -> 2
            0x00020002L -> 3
            else -> return null
        }
        val totalSize = le32(bytes, 0)
        val dataSize = le32(bytes, 4)
        if (totalSize > bytes.size || dataSize > totalSize || totalSize - dataSize < 12) return null
        val dataOffset = (totalSize - dataSize).toInt()
        if (le16(bytes, 34) != EOT_MAGIC) return null
        val flags = le32(bytes, 12)

        var at = 36 + 16 + 8 + 4 + 16 + 2
        if (at > dataOffset) return null
        val (family, afterFamily) = string(bytes, at, dataOffset) ?: return null
        val (style, afterStyle) = string(bytes, afterFamily + 2, dataOffset) ?: return null
        val (_, afterVersion) = string(bytes, afterStyle + 2, dataOffset) ?: return null
        val (full, afterFull) = string(bytes, afterVersion + 2, dataOffset) ?: return null
        if (version > 1) {
            string(bytes, afterFull + 2, dataOffset) ?: return null
        }

        val data = bytes.copyOfRange(dataOffset, totalSize.toInt())
        if (flags and XOR_ENCRYPTED.toLong() != 0L) {
            for (i in data.indices) data[i] = (data[i].toInt() xor XOR_KEY).toByte()
        }
        return EotContainer(family, style, full, data, flags and TT_COMPRESSED.toLong() != 0L)
    }

    private fun string(bytes: ByteArray, at: Int, limit: Int): Pair<String, Int>? {
        if (at + 2 > limit) return null
        val size = le16(bytes, at)
        if (size % 2 != 0 || at + 2 + size > limit) return null
        return String(bytes, at + 2, size, Charsets.UTF_16LE) to at + 2 + size
    }

    private fun le16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, at: Int): Long =
        le16(bytes, at).toLong() or (le16(bytes, at + 2).toLong() shl 16)
}
