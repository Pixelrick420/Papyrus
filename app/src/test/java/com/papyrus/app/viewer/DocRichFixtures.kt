package com.papyrus.app.viewer

import java.io.ByteArrayOutputStream

/**
 * Word 97-2003 documents with formatting: a style sheet and the paragraph property pages (PAPX) that
 * point paragraphs at it, laid out the way Word does so the extractor reads them through the same
 * offsets. Text is one piece (or two, to cross a piece boundary) placed by [DocFixtures.doc].
 */
internal object DocRichFixtures {

    /** A style-sheet entry. [papx] is the sprms of a paragraph style; [base] is the `istd` it is based on. */
    class Style(
        val name: String,
        val sti: Int,
        val base: Int = NO_BASE,
        val papx: ByteArray = ByteArray(0),
        val sgc: Int = SGC_PARAGRAPH,
    )

    /** One paragraph: [text] without its mark, the [mark] that ends it, its style and its direct sprms. */
    class Para(
        val text: String,
        val istd: Int = 0,
        val papx: ByteArray = ByteArray(0),
        val mark: Char = '\r',
    )

    val NORMAL = Style("Normal", sti = 0)
    val HEADING_1 = Style("Heading 1", sti = 1, base = 0)
    val HEADING_2 = Style("Heading 2", sti = 2, base = 0)
    val HEADING_3 = Style("Heading 3", sti = 3, base = 0)

    /** `istd` 0 to 3, as Word numbers them. */
    val DEFAULT_STYLES = listOf(NORMAL, HEADING_1, HEADING_2, HEADING_3)

    /**
     * A document of [paras]. A null entry in [styles] is an unused slot (`cbStd` 0). [splitAt] cuts the text
     * into two pieces at that character, the second UTF-16 whatever [compressed] says.
     */
    fun doc(
        paras: List<Para>,
        styles: List<Style?> = DEFAULT_STYLES,
        compressed: Boolean = true,
        splitAt: Int? = null,
    ): ByteArray {
        val text = paras.joinToString("") { it.text + it.mark }
        val pieces = if (splitAt == null) {
            listOf(DocFixtures.Piece(text, compressed))
        } else {
            listOf(DocFixtures.Piece(text.substring(0, splitAt), compressed), DocFixtures.Piece(text.substring(splitAt), false))
        }
        val width = if (compressed) 1 else 2
        val textBytes = pieces.sumOf { if (it.compressed) it.text.length else it.text.length * 2 }
        val firstPage = DocFixtures.tailOffset(textBytes) / PAGE
        var fc = DocFixtures.TEXT_OFFSET.toLong()
        val entries = ArrayList<PapxEntry>()
        var consumed = 0
        for (para in paras) {
            val chars = para.text.length + 1
            // Each character's width depends on the piece it falls in; the split piece is UTF-16.
            val bytes = (consumed until consumed + chars).sumOf { index ->
                if (splitAt != null && index >= splitAt) 2 else width
            }
            entries += PapxEntry(fc, fc + bytes, para.istd, para.papx)
            fc += bytes
            consumed += chars
        }
        val papx = papxPages(entries)
        return DocFixtures.doc(
            pieces = pieces,
            wordTail = papx.pages,
            tableParts = listOf(
                DocFixtures.TablePart(PAIR_STSHF, stsh(styles)),
                DocFixtures.TablePart(PAIR_BTE_PAPX, bte(papx.firstFcs, entries.last().to, firstPage)),
            ),
        )
    }

    // --- sprms -------------------------------------------------------------------------------

    /** A sprm with a 1-byte operand. */
    fun sprm(opcode: Int, operand: Int): ByteArray = byteArrayOf(lo(opcode), hi(opcode), operand.toByte())

    /** A sprm with a 2-byte operand. */
    fun sprmWord(opcode: Int, operand: Int): ByteArray = byteArrayOf(lo(opcode), hi(opcode), lo(operand), hi(operand))

    fun grpprl(vararg sprms: ByteArray): ByteArray = sprms.fold(ByteArray(0)) { acc, next -> acc + next }

    const val PJC = 0x2461
    const val PDXALEFT = 0x845E

    // --- structures ----------------------------------------------------------------------------

    private class PapxEntry(val from: Long, val to: Long, val istd: Int, val grpprl: ByteArray)

    private class PapxPages(val pages: ByteArray, val firstFcs: List<Long>)

    /** The STSH: a Stshi of 18 bytes, then each style's `cbStd` and STD. */
    private fun stsh(styles: List<Style?>): ByteArray {
        val out = ByteArrayOutputStream()
        out.u16(STSHI_BYTES)
        for (word in intArrayOf(styles.size, STDF_BASE_BYTES, 1, styles.size, styles.size, 0, 0, 0, 0)) out.u16(word)
        for (style in styles) {
            if (style == null) {
                out.u16(0)
                continue
            }
            val std = std(style)
            out.u16(std.size)
            out.write(std)
        }
        return out.toByteArray()
    }

    private fun std(style: Style): ByteArray {
        val out = ByteArrayOutputStream()
        out.u16(style.sti)
        out.u16(style.sgc or (style.base shl 4))
        out.u16(if (style.sgc == SGC_PARAGRAPH) 2 else 1)
        out.u16(0)
        out.u16(0)
        out.u16(style.name.length)
        out.write(style.name.toByteArray(Charsets.UTF_16LE))
        out.u16(0)
        // A paragraph style's UpxPapx is its own `istd` then the sprms; its UpxChpx follows, empty here.
        val papx = (if (style.sgc == SGC_PARAGRAPH) byteArrayOf(0, 0) + style.papx else ByteArray(0))
        if (style.sgc == SGC_PARAGRAPH) {
            out.upx(papx)
            out.upx(ByteArray(0))
        } else {
            out.upx(ByteArray(0))
        }
        return out.toByteArray()
    }

    /** A UPX is its length and its bytes, padded to an even length. */
    private fun ByteArrayOutputStream.upx(bytes: ByteArray) {
        u16(bytes.size)
        write(bytes)
        if (bytes.size % 2 != 0) write(0)
    }

    /**
     * PAPX FKP pages for [entries], which must tile the text. A PAPX is its size in words, the
     * `istd` and the sprms; a page holds at most as many paragraphs as fit, 29 at the most, with
     * the properties packed from the end of the page down and `crun` in the last byte.
     */
    private fun papxPages(entries: List<PapxEntry>): PapxPages {
        val out = ByteArrayOutputStream()
        val firsts = ArrayList<Long>()
        var i = 0
        while (i < entries.size) {
            var count = 0
            var used = 4
            while (i + count < entries.size && count < MAX_PAPX_PER_PAGE) {
                val need = 4 + PAPX_ENTRY_BYTES + stored(entries[i + count])
                if (used + need > PAGE - 2) break
                used += need
                count++
            }
            check(count > 0) { "a single paragraph's properties do not fit a page" }
            val page = ByteArray(PAGE)
            val group = entries.subList(i, i + count)
            firsts += group.first().from
            group.forEachIndexed { k, entry -> put32(page, k * 4, entry.from) }
            put32(page, count * 4, group.last().to)
            var top = PAGE - 2
            group.forEachIndexed { k, entry ->
                val props = byteArrayOf(lo(entry.istd), hi(entry.istd)) + entry.grpprl
                val body = if (props.size % 2 == 1) byteArrayOf(((props.size + 1) / 2).toByte()) + props
                else byteArrayOf(0, (props.size / 2).toByte()) + props
                top -= body.size
                body.copyInto(page, top)
                page[(count + 1) * 4 + k * PAPX_ENTRY_BYTES] = (top / 2).toByte()
            }
            page[PAGE - 1] = count.toByte()
            out.write(page)
            i += count
        }
        return PapxPages(out.toByteArray(), firsts)
    }

    private fun stored(entry: PapxEntry): Int {
        val props = 2 + entry.grpprl.size
        return if (props % 2 == 1) 1 + props else 2 + props
    }

    /** A PlcBtePapx: where each page starts, where the last ends, then the page numbers. */
    private fun bte(firstFcs: List<Long>, end: Long, firstPage: Int): ByteArray {
        val out = ByteArrayOutputStream()
        for (fc in firstFcs) out.u32(fc)
        out.u32(end)
        for (k in firstFcs.indices) out.u32((firstPage + k).toLong())
        return out.toByteArray()
    }

    private fun put32(page: ByteArray, at: Int, value: Long) {
        for (b in 0 until 4) page[at + b] = ((value shr (b * 8)) and 0xFF).toByte()
    }

    private fun ByteArrayOutputStream.u16(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.u32(value: Long) {
        for (b in 0 until 4) write(((value shr (b * 8)) and 0xFF).toInt())
    }

    private fun lo(value: Int): Byte = (value and 0xFF).toByte()

    private fun hi(value: Int): Byte = ((value shr 8) and 0xFF).toByte()

    const val NO_BASE = 0xFFF
    private const val PAIR_STSHF = 1
    private const val PAIR_BTE_PAPX = 13
    private const val SGC_PARAGRAPH = 1
    private const val STSHI_BYTES = 18
    private const val STDF_BASE_BYTES = 10
    private const val PAGE = 512
    private const val PAPX_ENTRY_BYTES = 13
    private const val MAX_PAPX_PER_PAGE = 29
}
