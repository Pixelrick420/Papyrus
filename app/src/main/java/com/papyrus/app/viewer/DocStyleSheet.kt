package com.papyrus.app.viewer

/**
 * The style sheet (STSH) from the table stream: each style's identity, what it is based on, and its
 * paragraph sprms. An unreadable sheet is an empty one, so a damaged style table costs a document its
 * headings and nothing else.
 *
 * STSH is `cbStshi`, the Stshi (whose first fields are `cstd`, the style count, and
 * `cbSTDBaseInFile`, the size of each STD's fixed part), then `cstd` times a 2-byte `cbStd` and an STD
 * of that size; a `cbStd` of 0 is an unused slot. An STD is the fixed StdfBase (`sti`, `sgc`,
 * `istdBase`, `cupx`), a length-prefixed name and its terminator, then `cupx` UPXs, each a 2-byte
 * length and its bytes, padded to an even length.
 */
internal class DocStyleSheet private constructor(private val styles: Array<Style?>) {

    private class Style(
        /** Built-in style identifier: 1 to 9 are Heading 1 to 9 in every language. */
        val sti: Int,
        /** Style group (`sgc`): 1 for a paragraph style. */
        val kind: Int,
        /** `istd` of the style this one is based on. */
        val base: Int,
        val name: String,
        /** Sprms of a paragraph style's UpxPapx, past its own `istd`. */
        val papx: ByteArray?,
        /** Sprms of the style's UpxChpx: the text formatting a paragraph or character style gives. */
        val chpx: ByteArray?,
    )

    private val resolved = arrayOfNulls<DocParaProps>(styles.size)

    /** Properties of a paragraph in style [istd], with its direct sprms `buf[from, end)` applied over the style's. */
    fun paragraph(istd: Int, buf: ByteArray, from: Int, end: Int): DocParaProps {
        val out = ofStyle(istd).copyFor(istd)
        out.applyGrpprl(buf, from, end)
        return out
    }

    /** What paragraph style [istd] gives a paragraph before its own sprms; Normal's if there is no such style. */
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

    /**
     * Character sprms of style [istd] and of every style it is based on, root first, so applying them in
     * order gives the style's formatting. None for a style the sheet lacks.
     */
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

    /** The first heading in [chain]: a style based on Heading 2 is a second-level heading too. */
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
        /** FibRgFcLcb97 index of fcStshf. */
        const val PAIR_STSHF = 1

        val EMPTY = DocStyleSheet(emptyArray())

        private const val MAX_STSH_BYTES = 4 * 1024 * 1024

        /** Two bytes of `cbStshi`, then the Stshi's `cstd` and `cbSTDBaseInFile`. */
        private const val MIN_STSH_BYTES = 6

        /** `istd` is 12 bits wide. */
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

        /** Reads the STSH, or returns [EMPTY] if the FIB has none or it is unusable. */
        fun parse(cfb: CompoundFile, fib: DocFib): DocStyleSheet {
            val lcb = fib.lcb(PAIR_STSHF)
            if (lcb < MIN_STSH_BYTES || lcb > MAX_STSH_BYTES) return EMPTY
            val data = cfb.readOrNull(fib.tableStream, fib.fc(PAIR_STSHF), lcb.toInt()) ?: return EMPTY
            return parse(data)
        }

        /** Parses an STSH already in memory; styles past a truncation are simply absent. */
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
