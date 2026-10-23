package com.papyrus.app.viewer

/**
 * The font table (SttbfFfn): face names by the index character sprms use, kept to tell a monospaced
 * face from a proportional one. It is `cData` and `cbExtra`, then `cData` FFNs, each a length byte
 * (the size of the rest), 39 bytes of metrics, and the face name as NUL-terminated UTF-16.
 */
internal class DocFontTable private constructor(private val names: List<String>) {

    /** True if font [index] is a monospaced face; false for one that is not, or that the table lacks. */
    fun isMono(index: Int): Boolean = isMonoFont(names.getOrNull(index))

    companion object {
        /** FibRgFcLcb97 index of fcSttbfFfn. */
        const val PAIR_STTBFFFN = 15

        val EMPTY = DocFontTable(emptyList())

        private const val MAX_BYTES = 1024 * 1024
        private const val HEADER_BYTES = 4
        private const val NAME_OFFSET = 40
        private const val MAX_FONTS = 4096

        fun parse(cfb: CompoundFile, fib: DocFib): DocFontTable {
            val lcb = fib.lcb(PAIR_STTBFFFN)
            if (lcb < HEADER_BYTES || lcb > MAX_BYTES) return EMPTY
            val data = cfb.readOrNull(fib.tableStream, fib.fc(PAIR_STTBFFFN), lcb.toInt()) ?: return EMPTY
            return parse(data)
        }

        fun parse(data: ByteArray): DocFontTable {
            if (data.size < HEADER_BYTES) return EMPTY
            val count = u16(data, 0).coerceAtMost(MAX_FONTS)
            val names = ArrayList<String>(count)
            var pos = HEADER_BYTES
            for (i in 0 until count) {
                if (pos >= data.size) break
                val end = pos + (data[pos].toInt() and 0xFF) + 1
                if (end > data.size) break
                names += faceName(data, pos + NAME_OFFSET, end)
                pos = end
            }
            return DocFontTable(names)
        }

        /** The face name in `[from, end)`, up to its NUL; an entry too short to hold one has none. */
        private fun faceName(data: ByteArray, from: Int, end: Int): String {
            var stop = from
            while (stop + 1 < end && (data[stop].toInt() != 0 || data[stop + 1].toInt() != 0)) stop += 2
            return if (stop > from) String(data, from, stop - from, Charsets.UTF_16LE) else ""
        }
    }
}
