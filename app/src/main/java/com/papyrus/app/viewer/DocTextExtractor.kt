package com.papyrus.app.viewer

import java.io.IOException
import java.io.InputStream

/**
 * Dependency-free Word 97-2003 `.doc` extraction: walks the OLE2 [CompoundFile], follows the FIB's
 * `fcClx` into the piece table, and emits the same [OfficeBlock] list as `OfficeTextExtractor`.
 * The binary formats are read by [DocFibParser] and [DocPieceTable]; [DocBlockBuilder] turns the
 * decoded text into blocks.
 */
object DocTextExtractor {

    /** Mirrors `OfficeTextExtractor.MAX_BLOCKS` so both paths cap identically. */
    const val MAX_BLOCKS = 20_000

    /** Mirrors `OfficeTextExtractor.MAX_TABLE_ROWS`; past it the table is dropped whole. */
    const val MAX_TABLE_ROWS = 2_000

    /** Characters decoded before extraction stops: a crafted file can claim a multi-gigabyte body,
     * and truncation beats an out-of-memory crash. */
    const val MAX_TOTAL_CHARS = 16_000_000

    private const val MAX_CLX_BYTES = 16 * 1024 * 1024

    /** [openStream] must return a fresh stream on every call; the caller closes it. [maxBlocks]
     * bounds the piece loop: the viewer keeps the full [MAX_BLOCKS], a thumbnail only its first lines. */
    fun extract(openStream: () -> InputStream, maxBlocks: Int = MAX_BLOCKS): List<OfficeBlock> {
        val fileLength = probeLength(openStream)
        val cfb = CompoundFile(openStream, fileLength)
        val fib = DocFibParser.parse(cfb)
        if (fib.ccpText == 0) return emptyList()
        val pieces = DocPieceTable.parse(readClx(cfb, fib))
        val builder = DocBlockBuilder(maxBlocks, DocListTables.parse(cfb, fib))
        decodePieces(cfb, fib, pieces, DocFormattingParser.open(cfb, fib), builder)
        return builder.finish()
    }

    private fun readClx(cfb: CompoundFile, fib: DocFib): ByteArray {
        val lcbClx = fib.lcb(DocFib.PAIR_CLX)
        if (lcbClx == 0L) throw IOException("document has no piece table")
        if (lcbClx > MAX_CLX_BYTES) throw IOException("piece table is too large")
        return cfb.read(fib.tableStream, fib.fc(DocFib.PAIR_CLX), lcbClx.toInt())
    }

    private fun decodePieces(
        cfb: CompoundFile,
        fib: DocFib,
        pieces: DocPieceTable,
        formatting: DocFormattingParser,
        builder: DocBlockBuilder,
    ) {
        val ccp = fib.ccpText.toLong()
        var consumed = 0L
        for (i in 0 until pieces.count) {
            if (builder.full || consumed >= MAX_TOTAL_CHARS) return
            val cpStart = pieces.cpStart(i)
            val cpEnd = pieces.cpEnd(i)
            if (cpEnd < cpStart) throw IOException("invalid character positions")
            // Pieces past ccpText cover footnote/header ranges this extractor deliberately skips.
            if (cpStart >= ccp) return
            val count = minOf(cpEnd, ccp) - cpStart
            if (count <= 0) continue
            val use = minOf(count, MAX_TOTAL_CHARS - consumed)
            consumed += use
            val compressed = pieces.isCompressed(i)
            val byteLen = (if (compressed) use else use * 2).toInt()
            val offset = pieces.byteOffset(i)
            val raw = cfb.read(DocFib.WORD_DOCUMENT, offset, byteLen)
            val text = if (compressed) decodeCompressed(raw) else String(raw, Charsets.UTF_16LE)
            builder.feed(text, formatting.runs(offset, text.length, if (compressed) 1 else 2))
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
