package io.github.pixelrick420.papyrus.viewer.pptx.text

/**
 * The values PowerPoint needs from a font file: `head.unitsPerEm`, `hhea` and the `OS/2` table.
 *
 * Android's `Paint` exposes ascent and descent but never `usWinAscent`/`usWinDescent`, so they are
 * read here for line-height behaviour that follows PowerPoint instead of the platform.
 */
internal class SfntMetrics(
    val unitsPerEm: Int,
    val ascender: Int,
    val descender: Int,
    val lineGap: Int,
    val typoAscender: Int? = null,
    val typoDescender: Int? = null,
    val typoLineGap: Int? = null,
    val winAscent: Int? = null,
    val winDescent: Int? = null,
    val selection: Int? = null,
) {

    val bold: Boolean get() = (selection ?: 0).and(BOLD_BIT) != 0

    val italic: Boolean get() = (selection ?: 0).and(ITALIC_BIT) != 0

    /** `fsSelection` bit 7: the `sTypo*` values outrank `usWin*` when the line height is chosen. */
    val useTypoMetrics: Boolean get() = (selection ?: 0).and(USE_TYPO_METRICS_BIT) != 0

    val ascentEm: Double
        get() = em(if (useTypoMetrics) typoAscender ?: ascender else winAscent ?: ascender)

    val descentEm: Double
        get() = if (useTypoMetrics) -em(typoDescender ?: descender) else em(winDescent ?: -descender)

    val lineGapEm: Double
        get() = em(if (useTypoMetrics) typoLineGap ?: lineGap else lineGap)

    val lineHeightEm: Double get() = ascentEm + descentEm + lineGapEm

    fun em(value: Int): Double = value.toDouble() / unitsPerEm

    companion object {

        private const val BOLD_BIT = 0x20

        private const val ITALIC_BIT = 0x01

        private const val USE_TYPO_METRICS_BIT = 0x80

        private const val SFNT_VERSION = 0x00010000L

        private const val CFF_VERSION = 0x4F54544FL

        private const val TABLE_RECORD_BYTES = 16

        private const val TABLE_DIRECTORY_BYTES = 12

        private const val HEAD_BYTES = 54

        private const val HHEA_BYTES = 36

        private const val OS2_BYTES = 78

        private const val UNITS_PER_EM_OFFSET = 18

        private const val HHEA_ASCENDER_OFFSET = 4

        private const val OS2_SELECTION_OFFSET = 62

        private const val OS2_TYPO_OFFSET = 68

        private const val OS2_WIN_OFFSET = 74

        /** Reads [bytes] as a TrueType or CFF-flavoured sfnt, or returns null when it is neither. */
        fun parse(bytes: ByteArray): SfntMetrics? {
            val tables = tables(bytes) ?: return null
            val head = tables["head"]?.takeIf { it.length >= HEAD_BYTES } ?: return null
            val hhea = tables["hhea"]?.takeIf { it.length >= HHEA_BYTES } ?: return null
            val unitsPerEm = bytes.u16(head.offset + UNITS_PER_EM_OFFSET)
            if (unitsPerEm == 0) return null
            val os2 = tables["OS/2"]?.takeIf { it.length >= OS2_BYTES }
            return SfntMetrics(
                unitsPerEm = unitsPerEm,
                ascender = bytes.i16(hhea.offset + HHEA_ASCENDER_OFFSET),
                descender = bytes.i16(hhea.offset + HHEA_ASCENDER_OFFSET + 2),
                lineGap = bytes.i16(hhea.offset + HHEA_ASCENDER_OFFSET + 4),
                typoAscender = os2?.let { bytes.i16(it.offset + OS2_TYPO_OFFSET) },
                typoDescender = os2?.let { bytes.i16(it.offset + OS2_TYPO_OFFSET + 2) },
                typoLineGap = os2?.let { bytes.i16(it.offset + OS2_TYPO_OFFSET + 4) },
                winAscent = os2?.let { bytes.u16(it.offset + OS2_WIN_OFFSET) },
                winDescent = os2?.let { bytes.u16(it.offset + OS2_WIN_OFFSET + 2) },
                selection = os2?.let { bytes.u16(it.offset + OS2_SELECTION_OFFSET) },
            )
        }

        private class Table(val offset: Int, val length: Int)

        private fun tables(bytes: ByteArray): Map<String, Table>? {
            if (bytes.size < TABLE_DIRECTORY_BYTES) return null
            if (bytes.u32(0) != SFNT_VERSION && bytes.u32(0) != CFF_VERSION) return null
            val count = bytes.u16(4)
            if (bytes.size < TABLE_DIRECTORY_BYTES + count * TABLE_RECORD_BYTES) return null
            val tables = HashMap<String, Table>(count)
            for (i in 0 until count) {
                val record = TABLE_DIRECTORY_BYTES + i * TABLE_RECORD_BYTES
                val offset = bytes.u32(record + 8)
                val length = bytes.u32(record + 12)
                if (offset + length > bytes.size) continue
                tables[String(bytes, record, 4, Charsets.US_ASCII)] = Table(offset.toInt(), length.toInt())
            }
            return tables
        }
    }
}

private fun ByteArray.u16(at: Int): Int = ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)

private fun ByteArray.i16(at: Int): Int = u16(at).toShort().toInt()

private fun ByteArray.u32(at: Int): Long =
    (u16(at).toLong() shl 16) or u16(at + 2).toLong()
