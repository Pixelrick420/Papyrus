package com.papyrus.app.viewer

import java.io.IOException

/**
 * The parts of the Word 97-2003 File Information Block the extractor reads. [ccpText] is the length
 * of the main document in characters; [fcLcb] holds the FibRgFcLcb97 `(fc, lcb)` pairs, two longs
 * each, so an offset in the table stream and its byte length are both a lookup away.
 */
internal class DocFib(val ccpText: Int, val tableStream: String, private val fcLcb: LongArray) {

    /** Offset in the table stream of FibRgFcLcb97 entry [pair], or 0 if the FIB is too short to have it. */
    fun fc(pair: Int): Long = if (pair * 2 + 1 < fcLcb.size) fcLcb[pair * 2] else 0L

    /** Byte length of FibRgFcLcb97 entry [pair], or 0 if the FIB is too short to have it. */
    fun lcb(pair: Int): Long = if (pair * 2 + 1 < fcLcb.size) fcLcb[pair * 2 + 1] else 0L

    companion object {
        const val WORD_DOCUMENT = "WordDocument"

        /** FibRgFcLcb97 index of fcClx: fcDop(31), fcSttbfAssoc(32), fcClx(33). */
        const val PAIR_CLX = 33
    }
}

/** Reads the FIB at the head of the `WordDocument` stream, and rejects what the extractor cannot read. */
internal object DocFibParser {

    fun parse(cfb: CompoundFile): DocFib {
        var head = cfb.read(DocFib.WORD_DOCUMENT, 0, FIB_HEAD)
        // The FIB grows past the first sector (standard layout ends at 0x382); re-read the prefix
        // rather than reading a fixed maximum, so a hostile `csw` cannot ask for a huge allocation.
        fun ensure(size: Int) {
            if (head.size < size) head = cfb.read(DocFib.WORD_DOCUMENT, 0, size)
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
        if (pairs <= DocFib.PAIR_CLX) throw IOException("unsupported FIB layout")
        val rgFcLcbBase = pairsOffset + 2
        ensure(rgFcLcbBase + pairs * 8)
        val ccpText = i32(head, rglwBase + FIB_CCPTEXT_OFFSET)
        if (ccpText < 0) throw IOException("invalid text length")
        // Only the pairs the extractor knows are kept: later ones are Word 2000+ additions.
        val kept = minOf(pairs, KEPT_PAIRS)
        val fcLcb = LongArray(kept * 2) { u32(head, rgFcLcbBase + (it / 2) * 8 + (it % 2) * 4) }
        // A producer that names the wrong table stream still opens: try the flag's name first,
        // then the other, then fail — matching how Word reads both streams by name.
        val primary = if (flags and FLAG_WHICH_TABLE != 0) TABLE_STREAM_1 else TABLE_STREAM_0
        val secondary = if (primary == TABLE_STREAM_1) TABLE_STREAM_0 else TABLE_STREAM_1
        val table = when {
            cfb.hasStream(primary) -> primary
            cfb.hasStream(secondary) -> secondary
            else -> throw IOException("table stream not found")
        }
        return DocFib(ccpText, table, fcLcb)
    }

    /** Through fcPlfLfo (74): the last FibRgFcLcb97 entry any extractor stage reads. */
    private const val KEPT_PAIRS = 75

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
}
