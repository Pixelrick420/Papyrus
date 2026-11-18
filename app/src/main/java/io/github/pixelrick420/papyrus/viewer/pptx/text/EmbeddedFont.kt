package io.github.pixelrick420.papyrus.viewer.pptx.text

/** A face carried by the deck, named by the family in its own `name` table. */
internal data class EmbeddedFont(
    val name: String,
    val family: String,
    val style: FaceStyle,
    val bytes: ByteArray,
)

internal object EmbeddedFontDecoder {

    fun decode(name: String, bytes: ByteArray): EmbeddedFont? {
        val tables = sfntTables(bytes) ?: return null
        val names = names(bytes, tables["name"] ?: return null)
        val family = names[1] ?: return null
        val subfamily = names[2].orEmpty()
        val style = FaceStyle.of(
            bold = has(subfamily, "bold"),
            italic = has(subfamily, "italic") || has(subfamily, "oblique"),
        )
        return EmbeddedFont(name, family, style, bytes)
    }

    private fun has(text: String, word: String): Boolean =
        text.contains(word, ignoreCase = true)

    private fun sfntTables(bytes: ByteArray): Map<String, IntRange>? {
        if (bytes.size < 12) return null
        val version = be32(bytes, 0)
        if (version != 0x00010000L && version != 0x4F54544FL && version != 0x74727565L) return null
        val count = be16(bytes, 4)
        if (12 + count * 16 > bytes.size) return null
        val tables = HashMap<String, IntRange>(count)
        for (i in 0 until count) {
            val at = 12 + i * 16
            val offset = be32(bytes, at + 8).toInt()
            val length = be32(bytes, at + 12).toInt()
            if (offset < 0 || length < 0 || offset.toLong() + length > bytes.size) continue
            tables[String(bytes, at, 4, Charsets.US_ASCII)] = offset until offset + length
        }
        return tables
    }

    private fun names(bytes: ByteArray, table: IntRange): Map<Int, String> {
        if (table.last - table.first < 6) return emptyMap()
        val count = be16(bytes, table.first + 2)
        val strings = table.first + be16(bytes, table.first + 4)
        val found = HashMap<Int, String>()
        for (i in 0 until count) {
            val at = table.first + 6 + i * 12
            if (at + 12 > table.last) break
            val platform = be16(bytes, at)
            val id = be16(bytes, at + 6)
            val length = be16(bytes, at + 8)
            val offset = be16(bytes, at + 10)
            val start = strings + offset
            if (start < 0 || start.toLong() + length > bytes.size) continue
            if (found.containsKey(id) && platform != 3) continue
            val text = when (platform) {
                3 -> String(bytes, start, length, Charsets.UTF_16BE)
                else -> String(bytes, start, length, Charsets.ISO_8859_1)
            }.trim('\u0000')
            if (text.isNotEmpty() && (!found.containsKey(id) || platform == 3)) found[id] = text
        }
        return found
    }

    private fun be16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun be32(bytes: ByteArray, at: Int): Long =
        (be16(bytes, at).toLong() shl 16) or be16(bytes, at + 2).toLong()
}
