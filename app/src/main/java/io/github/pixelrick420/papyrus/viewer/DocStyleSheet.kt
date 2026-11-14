package io.github.pixelrick420.papyrus.viewer

// An unreadable sheet is an empty one, so a damaged style table costs a document its headings and nothing else.
internal class DocStyleSheet private constructor(private val styles: Array<Style?>) {

    private class Style(
        val sti: Int,
        val kind: Int,
        val base: Int,
        val name: String,
        val papx: ByteArray?,
        val chpx: ByteArray?,
    )

    private val resolved = arrayOfNulls<DocParaProps>(styles.size)

    fun paragraph(istd: Int, buf: ByteArray, from: Int, end: Int): DocParaProps {
        val out = ofStyle(istd).copyFor(istd)
        out.applyGrpprl(buf, from, end)
        return out
    }

    fun ofStyle(istd: Int): DocParaProps {
        val index = if (styles.getOrNull(istd) != null) istd else 0
        resolved.getOrNull(index)?.let { return it }
        val props = DocParaProps(index)
        // Applied root first, so each style overrides the one it is based on.
        val chain = chainOf(index)
        for (i in chain.indices.reversed()) {
            styles[chain[i]]?.papx?.let { props.applyGrpprl(it, 0, it.size) }
        }
        props.headingLevel = headingLevelOf(chain)
        if (index in resolved.indices) resolved[index] = props
        return props
    }

    fun charSprms(istd: Int): List<ByteArray> {
        val chain = chainOf(istd)
        return chain.indices.reversed().mapNotNull { styles[chain[it]]?.chpx }
    }

    fun has(istd: Int): Boolean = styles.getOrNull(istd) != null

    /** [istd] and the styles it is based on, nearest first; a cycle or an absurd depth ends the chain. */
    private fun chainOf(istd: Int): List<Int> {
        val out = ArrayList<Int>()
        var at = istd
        while (styles.getOrNull(at) != null && out.size < MAX_CHAIN && at !in out) {
            out += at
            at = styles[at]?.base ?: break
        }
        return out
    }

    private fun headingLevelOf(chain: List<Int>): Int {
        for (istd in chain) {
            val style = styles[istd] ?: continue
            if (style.kind != SGC_PARAGRAPH) continue
            val level = if (style.sti in STI_HEADING_1..STI_HEADING_9) {
                // Built-in headings are named by `sti`: the name may be absent or in another language.
                style.sti - STI_HEADING_1 + 1
            } else {
                HEADING_NAME.matchEntire(style.name.trim())?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
            if (level > 0) return level.coerceAtMost(MAX_HEADING_LEVEL)
        }
        return 0
    }

    companion object {
        const val PAIR_STSHF = 1

        val EMPTY = DocStyleSheet(emptyArray())

        private const val MAX_STSH_BYTES = 4 * 1024 * 1024

        private const val MIN_STSH_BYTES = 6

        private const val MAX_STYLES = 4096
        private const val MAX_CHAIN = 64
        private const val STDF_BASE_BYTES = 10
        private const val SGC_PARAGRAPH = 1
        private const val SGC_CHARACTER = 2
        private const val STI_HEADING_1 = 1
        private const val STI_HEADING_9 = 9
        private const val MAX_HEADING_LEVEL = 6
        private const val STI_MASK = 0x0FFF
        private const val ISTD_MASK = 0x0FFF
        private const val NIBBLE_MASK = 0x000F
        private const val BASE_SHIFT = 4
        private val HEADING_NAME = Regex("^heading ([1-9])$", RegexOption.IGNORE_CASE)

        fun parse(cfb: CompoundFile, fib: DocFib): DocStyleSheet {
            val lcb = fib.lcb(PAIR_STSHF)
            if (lcb < MIN_STSH_BYTES || lcb > MAX_STSH_BYTES) return EMPTY
            val data = cfb.readOrNull(fib.tableStream, fib.fc(PAIR_STSHF), lcb.toInt()) ?: return EMPTY
            return parse(data)
        }

        fun parse(data: ByteArray): DocStyleSheet {
            if (data.size < MIN_STSH_BYTES) return EMPTY
            val cbStshi = u16(data, 0)
            if (cbStshi < MIN_STSH_BYTES - 2 || 2 + cbStshi > data.size) return EMPTY
            val count = u16(data, 2).coerceAtMost(MAX_STYLES)
            val cbBase = u16(data, 4)
            if (cbBase < STDF_BASE_BYTES) return EMPTY
            val styles = arrayOfNulls<Style>(count)
            var pos = 2 + cbStshi
            for (istd in 0 until count) {
                if (pos + 2 > data.size) break
                val cbStd = u16(data, pos)
                pos += 2
                if (cbStd > data.size - pos) break
                if (cbStd >= cbBase + 2) styles[istd] = readStd(data, pos, cbStd, cbBase)
                pos += cbStd
            }
            return DocStyleSheet(styles)
        }

        private fun readStd(data: ByteArray, at: Int, size: Int, cbBase: Int): Style {
            val limit = at + size
            val sti = u16(data, at) and STI_MASK
            val word1 = u16(data, at + 2)
            val kind = word1 and NIBBLE_MASK
            val base = (word1 ushr BASE_SHIFT) and ISTD_MASK
            val cupx = u16(data, at + 4) and NIBBLE_MASK
            val nameAt = at + cbBase
            val nameChars = u16(data, nameAt)
            val nameEnd = nameAt + 2 + nameChars * 2
            val name = if (nameEnd + 2 <= limit) String(data, nameAt + 2, nameChars * 2, Charsets.UTF_16LE) else ""
            // The UPXs follow the name's two-byte terminator: a length, then that many bytes, padded to even.
            val upxs = ArrayList<ByteArray>(cupx)
            var pos = nameEnd + 2
            for (k in 0 until cupx) {
                if (pos + 2 > limit) break
                val len = u16(data, pos)
                if (pos + 2 + len > limit) break
                upxs += data.copyOfRange(pos + 2, pos + 2 + len)
                pos += 2 + len + (len and 1)
            }
            // A paragraph style has a UpxPapx (its own `istd`, then sprms) and a UpxChpx; a character style only the latter.
            val papx = if (kind == SGC_PARAGRAPH) upxs.getOrNull(0)?.takeIf { it.size >= 2 }?.copyOfRange(2, upxs[0].size) else null
            val chpx = when (kind) {
                SGC_PARAGRAPH -> upxs.getOrNull(1)
                SGC_CHARACTER -> upxs.getOrNull(0)
                else -> null
            }
            return Style(sti, kind, base, name, papx, chpx)
        }
    }
}
