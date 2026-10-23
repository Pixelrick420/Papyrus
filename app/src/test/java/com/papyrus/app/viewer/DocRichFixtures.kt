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

    /** A list level: number format [nfc] (0 decimal, 4 lower letter, 0x17 bullet, 0xFF none) and its template. */
    class ListLevel(val nfc: Int, val text: String, val start: Int = 1)

    /** A list definition (an LSTF and its LVLs): [levels] has nine entries, or one when [simple]. */
    class ListDef(val lsid: Long, val levels: List<ListLevel>, val simple: Boolean = false)

    /** An instance's change to one level: restart it at [startAt], and/or replace its formatting with [format]. */
    class LevelOverride(val level: Int, val startAt: Int? = null, val format: ListLevel? = null)

    /** An LFO: the `ilfo` of the n-th instance is n + 1. */
    class ListInstance(val lsid: Long, val overrides: List<LevelOverride> = emptyList())

    val NORMAL = Style("Normal", sti = 0)
    val HEADING_1 = Style("Heading 1", sti = 1, base = 0)
    val HEADING_2 = Style("Heading 2", sti = 2, base = 0)
    val HEADING_3 = Style("Heading 3", sti = 3, base = 0)

    /** `istd` 0 to 3, as Word numbers them. */
    val DEFAULT_STYLES = listOf(NORMAL, HEADING_1, HEADING_2, HEADING_3)

    /** Bullets at every level: the Symbol glyph Word stores, in the private-use area. */
    fun bullets(lsid: Long) = ListDef(lsid, List(LEVELS) { ListLevel(NFC_BULLET, "\uF0B7") })

    /** "1." then "a)" then "i." and decimals below: the shape of Word's default numbered list. */
    fun numbered(lsid: Long) = ListDef(
        lsid,
        listOf(ListLevel(0, "\u0000."), ListLevel(4, "\u0001)"), ListLevel(2, "\u0002.")) +
            (3 until LEVELS).map { ListLevel(0, "${it.toChar()}.") },
    )

    /** A paragraph in list instance [ilfo] at [ilvl]. */
    fun item(text: String, ilfo: Int, ilvl: Int = 0, istd: Int = 0, mark: Char = '\r') =
        Para(text, istd, grpprl(sprmWord(PILFO, ilfo), sprm(PILVL, ilvl)), mark)

    /**
     * A document of [paras]. A null entry in [styles] is an unused slot (`cbStd` 0). [splitAt] cuts the text
     * into two pieces at that character, the second UTF-16 whatever [compressed] says.
     */
    fun doc(
        paras: List<Para>,
        styles: List<Style?> = DEFAULT_STYLES,
        compressed: Boolean = true,
        splitAt: Int? = null,
        lists: List<ListDef> = emptyList(),
        instances: List<ListInstance> = emptyList(),
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
            ) + listParts(lists, instances),
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
    const val PILVL = 0x260A
    const val PILFO = 0x460B

    // --- structures ----------------------------------------------------------------------------

    /** The two list tables as the table stream holds them: [lst] runs on past its recorded length [lstLcb]. */
    class ListBytes(val lst: ByteArray, val lstLcb: Int, val lfo: ByteArray)

    /** PlfLst (whose recorded length stops before the LVLs that follow it) and PlfLfo. */
    fun listBytes(lists: List<ListDef>, instances: List<ListInstance>): ListBytes {
        val plfLst = ByteArrayOutputStream()
        plfLst.u16(lists.size)
        for (def in lists) {
            plfLst.u32(def.lsid)
            plfLst.u32(0) // tplc
            repeat(LEVELS) { plfLst.u16(NO_BASE) } // rgistd: no linked styles
            plfLst.write(if (def.simple) 1 else 0)
            plfLst.write(0) // grfhic
        }
        val lstfBytes = plfLst.size()
        for (def in lists) for (level in def.levels) plfLst.write(lvl(level))

        val plfLfo = ByteArrayOutputStream()
        plfLfo.u32(instances.size.toLong())
        for (instance in instances) {
            plfLfo.u32(instance.lsid)
            plfLfo.u32(0)
            plfLfo.u32(0)
            plfLfo.write(instance.overrides.size) // clfolvl
            repeat(3) { plfLfo.write(0) }
        }
        for (instance in instances) {
            plfLfo.u32(0xFFFFFFFFL) // cp
            for (override in instance.overrides) {
                plfLfo.u32((override.startAt ?: 0).toLong() and 0xFFFFFFFFL)
                var flags = override.level
                if (override.startAt != null) flags = flags or LFOLVL_START_AT
                if (override.format != null) flags = flags or LFOLVL_FORMATTING
                plfLfo.u32(flags.toLong())
                override.format?.let { plfLfo.write(lvl(it)) }
            }
        }
        return ListBytes(plfLst.toByteArray(), lstfBytes, plfLfo.toByteArray())
    }

    private fun listParts(lists: List<ListDef>, instances: List<ListInstance>): List<DocFixtures.TablePart> {
        if (lists.isEmpty() && instances.isEmpty()) return emptyList()
        val bytes = listBytes(lists, instances)
        return listOf(
            DocFixtures.TablePart(PAIR_PLFLST, bytes.lst, lcb = bytes.lstLcb),
            DocFixtures.TablePart(PAIR_PLFLFO, bytes.lfo),
        )
    }

    /** An LVL: a 28-byte LVLF (no paragraph or character sprms), then the number text. */
    private fun lvl(level: ListLevel): ByteArray {
        val out = ByteArrayOutputStream()
        out.u32(level.start.toLong() and 0xFFFFFFFFL)
        out.write(level.nfc)
        repeat(LVLF_BYTES - 5) { out.write(0) }
        out.u16(level.text.length)
        out.write(level.text.toByteArray(Charsets.UTF_16LE))
        return out.toByteArray()
    }

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
    const val LEVELS = 9
    const val NFC_BULLET = 0x17
    const val NFC_NONE = 0xFF
    private const val PAIR_STSHF = 1
    private const val PAIR_BTE_PAPX = 13
    private const val PAIR_PLFLST = 73
    private const val PAIR_PLFLFO = 74
    private const val LVLF_BYTES = 28
    private const val LFOLVL_START_AT = 0x10
    private const val LFOLVL_FORMATTING = 0x20
    private const val SGC_PARAGRAPH = 1
    private const val STSHI_BYTES = 18
    private const val STDF_BASE_BYTES = 10
    private const val PAGE = 512
    private const val PAPX_ENTRY_BYTES = 13
    private const val MAX_PAPX_PER_PAGE = 29
}
