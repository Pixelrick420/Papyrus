package com.papyrus.app.viewer

import java.io.IOException

/**
 * The PlcPcd of the Clx: which stretch of the `WordDocument` stream holds each run of characters.
 * Backed by the Clx bytes themselves, so a document with a million pieces costs no object per piece.
 */
internal class DocPieceTable private constructor(
    private val clx: ByteArray,
    private val plcBase: Int,
    val count: Int,
) {
    private val pcdBase = plcBase + (count + 1) * 4

    /** First character position (cp) of piece [i]. */
    fun cpStart(i: Int): Long = u32(clx, plcBase + i * 4)

    /** Character position just past piece [i]. */
    fun cpEnd(i: Int): Long = u32(clx, plcBase + (i + 1) * 4)

    /** The piece's `fc` as stored: bit 30 flags 8-bit text, the rest is the byte offset (doubled if 8-bit). */
    private fun fcRaw(i: Int): Long = u32(clx, pcdBase + i * 8 + 2)

    /** True when piece [i] is 8-bit (cp1252) text rather than UTF-16LE. */
    fun isCompressed(i: Int): Boolean = fcRaw(i) and FC_COMPRESSED != 0L

    /** Byte offset in `WordDocument` of piece [i]'s first character. */
    fun byteOffset(i: Int): Long {
        val fc = fcRaw(i) and FC_MASK
        return if (isCompressed(i)) fc / 2 else fc
    }

    companion object {
        private const val CLXT_PRC = 0x01
        private const val CLXT_PCDT = 0x02
        private const val MAX_PRC_GRPPRL = 0x3FA2
        private const val FC_COMPRESSED = 0x40000000L
        private const val FC_MASK = 0x3FFFFFFFL

        /** Parses [clx]: zero or more Prc chunks (`clxt` 1, a skipped `cbGrpprl` grpprl), then the Pcdt (`clxt` 2). */
        fun parse(clx: ByteArray): DocPieceTable {
            val (plcBase, plcLen) = findPcdt(clx)
            if ((plcLen - 4) % 12 != 0L) throw IOException("invalid piece table")
            return DocPieceTable(clx, plcBase, ((plcLen - 4) / 12).toInt())
        }

        /** Returns the PlcPcd location within [clx]. */
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
    }
}
