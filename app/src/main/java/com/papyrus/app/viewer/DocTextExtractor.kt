package com.papyrus.app.viewer

import java.io.IOException
import java.io.InputStream

/**
 * Dependency-free Word 97-2003 `.doc` extraction: walks the OLE2 [CompoundFile], follows the FIB's
 * `fcClx` into the piece table, and emits the same [OfficeBlock] list as `OfficeTextExtractor`.
 */
object DocTextExtractor {

    /** Mirrors `OfficeTextExtractor.MAX_BLOCKS` so both paths cap identically. */
    const val MAX_BLOCKS = 20_000

    /** Mirrors `OfficeTextExtractor.MAX_TABLE_ROWS`; past it the table is dropped whole. */
    const val MAX_TABLE_ROWS = 2_000

    /** Characters decoded before extraction stops: a crafted file can claim a multi-gigabyte body,
     * and truncation beats an out-of-memory crash. */
    const val MAX_TOTAL_CHARS = 16_000_000

    /** [openStream] must return a fresh stream on every call; the caller closes it. [maxBlocks]
     * bounds the piece loop: the viewer keeps the full [MAX_BLOCKS], a thumbnail only its first lines. */
    fun extract(openStream: () -> InputStream, maxBlocks: Int = MAX_BLOCKS): List<OfficeBlock> {
        val fileLength = probeLength(openStream)
        val cfb = CompoundFile(openStream, fileLength)
        val fib = readFib(cfb)
        if (fib.ccpText == 0) return emptyList()
        val clx = readClx(cfb, fib)
        val builder = BlockBuilder(maxBlocks)
        decodePieces(cfb, fib, clx, builder)
        return builder.finish()
    }

    private data class Fib(val ccpText: Int, val fcClx: Long, val lcbClx: Long, val tableStream: String)

    private fun readFib(cfb: CompoundFile): Fib {
        var head = cfb.read(WORD_DOCUMENT, 0, FIB_HEAD)
        // The FIB grows past the first sector (standard layout ends at 0x382); re-read the prefix
        // rather than reading a fixed maximum, so a hostile `csw` cannot ask for a huge allocation.
        fun ensure(size: Int) {
            if (head.size < size) head = cfb.read(WORD_DOCUMENT, 0, size)
        }

        fun u16At(offset: Int): Int {
            ensure(offset + 2)
            return u16(head, offset)
        }

        if (u16At(FIB_WIDENT_OFFSET) != WIDENT) throw IOException("not a Word document")
        if (u16At(FIB_NFIB_OFFSET) < NFIB_WORD97) throw IOException("Word 6/95 documents are not supported")
        val flags = u16At(FIB_FLAGS_OFFSET)
        if (flags and FLAG_ENCRYPTED != 0 || flags and FLAG_OBFUSCATED != 0) {
            throw IOException("password-protected documents are not supported")
        }
        val rgwEnd = FIB_FIBRGW_OFFSET + u16At(FIB_CSW_OFFSET) * 2
        val cslw = u16At(rgwEnd)
        if (cslw < 4) throw IOException("unsupported FIB layout")
        val rglwBase = rgwEnd + 2
        ensure(rglwBase + cslw * 4 + 2)
        val pairsOffset = rglwBase + cslw * 4
        val pairs = u16(head, pairsOffset)
        if (pairs <= FCCLX_PAIR) throw IOException("unsupported FIB layout")
        val rgFcLcbBase = pairsOffset + 2
        ensure(rgFcLcbBase + pairs * 8)
        val ccpText = i32(head, rglwBase + FIB_CCPTEXT_OFFSET)
        if (ccpText < 0) throw IOException("invalid text length")
        val fcClx = u32(head, rgFcLcbBase + FCCLX_PAIR * 8)
        val lcbClx = u32(head, rgFcLcbBase + FCCLX_PAIR * 8 + 4)
        // A producer that names the wrong table stream still opens: try the flag's name first,
        // then the other, then fail — matching how Word reads both streams by name.
        val primary = if (flags and FLAG_WHICH_TABLE != 0) TABLE_STREAM_1 else TABLE_STREAM_0
        val secondary = if (primary == TABLE_STREAM_1) TABLE_STREAM_0 else TABLE_STREAM_1
        val table = when {
            cfb.hasStream(primary) -> primary
            cfb.hasStream(secondary) -> secondary
            else -> throw IOException("table stream not found")
        }
        return Fib(ccpText, fcClx, lcbClx, table)
    }

    private fun readClx(cfb: CompoundFile, fib: Fib): ByteArray {
        if (fib.lcbClx == 0L) throw IOException("document has no piece table")
        if (fib.lcbClx > MAX_CLX_BYTES) throw IOException("piece table is too large")
        return cfb.read(fib.tableStream, fib.fcClx, fib.lcbClx.toInt())
    }

    /** Walks the Clx: zero or more Prc chunks (`clxt` 1, a skipped `cbGrpprl` grpprl), then the Pcdt
     * (`clxt` 2) holding the PlcPcd. Returns the PlcPcd location within [clx]. */
    private fun findPcdt(clx: ByteArray): Pair<Int, Long> {
        var pos = 0
        while (pos < clx.size) {
            when (clx[pos].toInt() and 0xFF) {
                CLXT_PRC -> {
                    if (pos + 3 > clx.size) throw IOException("truncated Clx")
                    val cb = u16(clx, pos + 1)
                    if (cb > MAX_PRC_GRPPRL) throw IOException("invalid Prc")
                    pos += 3 + cb
                    if (pos > clx.size) throw IOException("truncated Clx")
                }
                CLXT_PCDT -> {
                    if (pos + 5 > clx.size) throw IOException("truncated Clx")
                    val lcb = u32(clx, pos + 1)
                    val base = pos + 5
                    if (lcb < 4 || lcb > clx.size - base) throw IOException("truncated Clx")
                    return base to lcb
                }
                else -> throw IOException("invalid Clx")
            }
        }
        throw IOException("piece table missing")
    }

    private fun decodePieces(cfb: CompoundFile, fib: Fib, clx: ByteArray, builder: BlockBuilder) {
        val (plcBase, plcLen) = findPcdt(clx)
        if ((plcLen - 4) % 12 != 0L) throw IOException("invalid piece table")
        val pieceCount = (plcLen - 4) / 12
        val pcdBase = plcBase + ((pieceCount + 1) * 4).toInt()
        val ccp = fib.ccpText.toLong()
        var consumed = 0L
        var i = 0L
        while (i < pieceCount) {
            if (builder.full || consumed >= MAX_TOTAL_CHARS) return
            val cpStart = u32(clx, plcBase + (i * 4).toInt())
            val cpEnd = u32(clx, plcBase + ((i + 1) * 4).toInt())
            if (cpEnd < cpStart) throw IOException("invalid character positions")
            i++
            // Pieces past ccpText cover footnote/header ranges this extractor deliberately skips.
            if (cpStart >= ccp) return
            val count = minOf(cpEnd, ccp) - cpStart
            if (count <= 0) continue
            val use = minOf(count, MAX_TOTAL_CHARS - consumed)
            consumed += use
            val fcRaw = u32(clx, pcdBase + ((i - 1) * 8).toInt() + 2)
            val compressed = fcRaw and FC_COMPRESSED != 0L
            val fc = fcRaw and FC_MASK
            val offset = if (compressed) fc / 2 else fc
            val byteLen = (if (compressed) use else use * 2).toInt()
            val raw = cfb.read(WORD_DOCUMENT, offset, byteLen)
            builder.feed(if (compressed) decodeCompressed(raw) else String(raw, Charsets.UTF_16LE))
        }
    }

    /**
     * Text → blocks. A cell ends with a cell mark (0x07) and the row's TTP follows the last cell's
     * mark, so `cell\x07cell\x07\x07` is one row; nested tables arrive as plain paragraph marks.
     */
    private class BlockBuilder(private val maxBlocks: Int) {
        private enum class State { PARA, ROW, BETWEEN }

        private val blocks = mutableListOf<OfficeBlock>()
        private val paragraph = StringBuilder()
        private val cell = StringBuilder()
        private val row = mutableListOf<String>()
        private val rows = mutableListOf<List<String>>()
        /** Per-depth flag: field instructions (between 0x13 and 0x14) are not content. */
        private val fieldDepth = ArrayDeque<Boolean>()
        private var state = State.PARA
        private var tableUsable = true

        /** Set once the block budget is reached, so the piece loop stops instead of feeding a dead builder. */
        var full = false
            private set

        fun feed(text: String) {
            for (ch in text) {
                if (blocks.size >= maxBlocks) {
                    full = true
                    return
                }
                append(ch)
            }
        }

        fun finish(): List<OfficeBlock> {
            if (state == State.ROW) {
                if (cell.isNotEmpty()) row += cell.toString().trim('\n')
                commitRow()
            }
            flushTable()
            flushParagraph()
            return blocks
        }

        private fun append(ch: Char) {
            when (ch) {
                FIELD_BEGIN -> {
                    if (fieldDepth.size < MAX_FIELD_DEPTH) fieldDepth.addLast(false)
                    return
                }
                FIELD_SEPARATOR -> {
                    if (fieldDepth.isNotEmpty()) fieldDepth[fieldDepth.lastIndex] = true
                    return
                }
                FIELD_END -> {
                    if (fieldDepth.isNotEmpty()) fieldDepth.removeLast()
                    return
                }
                // Marks are honoured even inside a field instruction: structure survives a
                // malformed field, while its text is dropped by the check below.
                '\r', LINE_BREAK, PAGE_BREAK -> {
                    paragraphMark()
                    return
                }
                CELL_MARK -> {
                    cellMark()
                    return
                }
            }
            if (fieldDepth.isNotEmpty() && !fieldDepth.last()) return
            when {
                ch == '\t' -> target().append('\t')
                ch.code >= 0x20 -> target().append(ch)
            }
        }

        private fun target(): StringBuilder = if (state == State.ROW) cell else paragraph

        private fun paragraphMark() {
            when (state) {
                State.ROW -> cell.append('\n')
                State.BETWEEN -> {
                    // The paragraph after a TTP proves the table ended; a next row would have
                    // arrived as a cell mark first.
                    flushTable()
                    flushParagraph()
                    state = State.PARA
                }
                State.PARA -> flushParagraph()
            }
        }

        private fun cellMark() {
            when (state) {
                State.PARA, State.BETWEEN -> {
                    row += paragraph.toString()
                    paragraph.setLength(0)
                    cell.setLength(0)
                    state = State.ROW
                }
                State.ROW -> if (cell.isNotEmpty()) {
                    row += cell.toString().trim('\n')
                    cell.setLength(0)
                } else {
                    commitRow()
                    state = State.BETWEEN
                }
            }
        }

        private fun commitRow() {
            if (row.isNotEmpty() && rows.size < MAX_TABLE_ROWS) rows += row.toList()
            tableUsable = rows.size < MAX_TABLE_ROWS
            row.clear()
        }

        private fun flushTable() {
            if (rows.isNotEmpty() && tableUsable && blocks.size < maxBlocks) {
                blocks += OfficeBlock.Table(rows.map { r -> r.mapIndexed { i, text -> OfficeCell(text, column = i) } })
            }
            rows.clear()
            tableUsable = true
        }

        private fun flushParagraph() {
            val text = paragraph.toString()
            if (text.isNotBlank() && blocks.size < maxBlocks) blocks += OfficeBlock.Paragraph(text)
            paragraph.setLength(0)
        }
    }
}

/** FcCompressed 8-bit text: cp1252 specials map to Unicode, the rest are Latin-1. */
private fun decodeCompressed(bytes: ByteArray): String {
    val out = CharArray(bytes.size)
    for (i in bytes.indices) out[i] = compressedChar(bytes[i].toInt() and 0xFF)
    return String(out)
}

private fun compressedChar(b: Int): Char = when (b) {
    0x82 -> '\u201A'
    0x83 -> '\u0192'
    0x84 -> '\u201E'
    0x85 -> '\u2026'
    0x86 -> '\u2020'
    0x87 -> '\u2021'
    0x88 -> '\u02C6'
    0x89 -> '\u2030'
    0x8A -> '\u0160'
    0x8B -> '\u2039'
    0x8C -> '\u0152'
    0x91 -> '\u2018'
    0x92 -> '\u2019'
    0x93 -> '\u201C'
    0x94 -> '\u201D'
    0x95 -> '\u2022'
    0x96 -> '\u2013'
    0x97 -> '\u2014'
    0x98 -> '\u02DC'
    0x99 -> '\u2122'
    0x9A -> '\u0161'
    0x9B -> '\u203A'
    0x9C -> '\u0153'
    0x9F -> '\u0178'
    else -> b.toChar()
}

private const val MAX_CLX_BYTES = 16 * 1024 * 1024
private const val MAX_PRC_GRPPRL = 0x3FA2

private const val WORD_DOCUMENT = "WordDocument"
private const val TABLE_STREAM_0 = "0Table"
private const val TABLE_STREAM_1 = "1Table"

private const val WIDENT = 0xA5EC
private const val NFIB_WORD97 = 0x00C1
private const val FLAG_ENCRYPTED = 0x0100
private const val FLAG_WHICH_TABLE = 0x0200
private const val FLAG_OBFUSCATED = 0x8000

private const val FIB_HEAD = 512
private const val FIB_WIDENT_OFFSET = 0x00
private const val FIB_NFIB_OFFSET = 0x02
private const val FIB_FLAGS_OFFSET = 0x0A
private const val FIB_CSW_OFFSET = 0x20
private const val FIB_FIBRGW_OFFSET = 0x22
private const val FIB_CCPTEXT_OFFSET = 12
/** FibRgFcLcb97 index of fcClx: fcDop(31), fcSttbfAssoc(32), fcClx(33). */
private const val FCCLX_PAIR = 33

private const val CLXT_PRC = 0x01
private const val CLXT_PCDT = 0x02
private const val FC_COMPRESSED = 0x40000000L
private const val FC_MASK = 0x3FFFFFFFL

private const val FIELD_BEGIN = '\u0013'
private const val FIELD_SEPARATOR = '\u0014'
private const val FIELD_END = '\u0015'
private const val CELL_MARK = '\u0007'
private const val LINE_BREAK = '\u000B'
private const val PAGE_BREAK = '\u000C'
private const val MAX_FIELD_DEPTH = 32
