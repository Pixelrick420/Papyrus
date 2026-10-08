package com.papyrus.app.viewer

import java.io.IOException
import java.io.InputStream

/**
 * Dependency-free extraction for Word 97-2003 `.doc`. The file is an OLE2 compound document, so
 * the container is walked first (header → DIFAT → FAT → directory), then the FIB is read from the
 * `WordDocument` stream and its `fcClx` followed into the `0Table`/`1Table` stream for the piece
 * table. Decoded pieces become the same [OfficeBlock] list `OfficeTextExtractor` produces, so the
 * viewer, thumbnail and find-in-file surfaces are shared unchanged.
 *
 * Read-only: sector chains are read through the fresh-stream factory (the caller closes each
 * stream) and never buffered whole, and output is bounded by [MAX_BLOCKS] / [MAX_TOTAL_CHARS]
 * the way the ZIP extractor is bounded. Every malformed input surfaces as [IOException], which
 * the existing catch arms map to `Failed` (viewer) and `Unavailable` (thumbnail).
 */
object DocTextExtractor {

    /** Mirrors `OfficeTextExtractor.MAX_BLOCKS` so both paths cap identically. */
    const val MAX_BLOCKS = 20_000

    /** Mirrors `OfficeTextExtractor.MAX_TABLE_ROWS`; past it the table is dropped whole. */
    const val MAX_TABLE_ROWS = 2_000

    /**
     * Characters decoded before extraction stops. A crafted file can claim a multi-gigabyte body;
     * real documents sit far below this, and truncation beats an out-of-memory crash.
     */
    const val MAX_TOTAL_CHARS = 16_000_000

    /** [openStream] must return a fresh stream on every call; the caller closes it. */
    fun extract(openStream: () -> InputStream): List<OfficeBlock> {
        val fileLength = probeLength(openStream)
        val cfb = CompoundFile(openStream, fileLength)
        val fib = readFib(cfb)
        if (fib.ccpText == 0) return emptyList()
        val clx = readClx(cfb, fib)
        val builder = BlockBuilder()
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

    /**
     * Walks the Clx: zero or more Prc chunks (`clxt` 1, a skipped `cbGrpprl` grpprl) followed by
     * the Pcdt (`clxt` 2) that holds the PlcPcd. Returns the PlcPcd location within [clx].
     */
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
     * Text → blocks. Word's depth-1 table syntax is visible in the text stream: a cell ends with
     * a cell mark (0x07) and the row's TTP immediately follows the last cell's mark, so
     * `cell\x07cell\x07\x07` is one row — adjacent marks mean TTP. Nested tables carry their
     * depth in property sprms the text stream never shows, so their boundaries arrive as plain
     * paragraph marks and join their cell with a newline instead.
     */
    private class BlockBuilder {
        private enum class State { PARA, ROW, BETWEEN }

        private val blocks = mutableListOf<OfficeBlock>()
        private val paragraph = StringBuilder()
        private val cell = StringBuilder()
        private val row = mutableListOf<String>()
        private val rows = mutableListOf<List<String>>()
        /** Empty or result-visible per depth: field instructions (between 0x13 and 0x14) are not content. */
        private val fieldDepth = ArrayDeque<Boolean>()
        private var state = State.PARA
        private var tableUsable = true

        /** Set once [MAX_BLOCKS] is reached so the piece loop stops decoding instead of feeding a dead builder. */
        var full = false
            private set

        fun feed(text: String) {
            for (ch in text) {
                if (blocks.size >= MAX_BLOCKS) {
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
            if (rows.isNotEmpty() && tableUsable && blocks.size < MAX_BLOCKS) {
                blocks += OfficeBlock.Table(rows.map { r -> r.mapIndexed { i, text -> OfficeCell(text, column = i) } })
            }
            rows.clear()
            tableUsable = true
        }

        private fun flushParagraph() {
            val text = paragraph.toString()
            if (text.isNotBlank() && blocks.size < MAX_BLOCKS) blocks += OfficeBlock.Paragraph(text)
            paragraph.setLength(0)
        }
    }

    /**
     * Minimal OLE2/CFB reader: header validation, DIFAT/FAT assembly, a linear directory scan,
     * and random reads over a stream's sector chain (mini streams through the root's mini FAT).
     * Every read opens a fresh stream through [openStream] and bounds-checks against [fileLength],
     * so a truncated or cyclic file throws [IOException] instead of looping or allocating on
     * hostile sizes.
     */
    private class CompoundFile(private val openStream: () -> InputStream, private val fileLength: Long) {
        private class Entry(val name: String, val type: Int, val start: Int, val size: Long)
        private class Chunk(val fileOffset: Long, val dest: Int, val length: Int)

        private val sectorSize: Int
        private val totalSectors: Int
        private val fat: IntArray
        private val entries: List<Entry>
        private val miniCutoff: Long
        private val firstMiniFat: Int
        private val root: Entry?

        /** Sector ids of the mini FAT, or null when the file has no mini stream to reach. */
        private val miniFat: IntArray? by lazy {
            if (firstMiniFat == ENDOFCHAIN) return@lazy null
            join(rawMany(chain(firstMiniFat, fat, totalSectors, "mini FAT").map(::sectorOffset), sectorSize))
        }

        /** Sector ids of the root entry's stream, which mini streams are byte slices of. */
        private val miniRoot: List<Int> by lazy {
            val r = root ?: throw IOException("missing root entry")
            if (r.start == ENDOFCHAIN || r.size == 0L) throw IOException("missing mini stream")
            chain(r.start, fat, totalSectors, "mini stream")
        }

        init {
            if (fileLength < HEADER_SIZE) throw IOException("truncated compound file")
            val header = raw(0, HEADER_SIZE)
            if (OLE_SIGNATURE.indices.any { (header[it].toInt() and 0xFF) != OLE_SIGNATURE[it] }) {
                throw IOException("not a compound file")
            }
            if (u16(header, BYTE_ORDER_OFFSET) != BYTE_ORDER_LE) throw IOException("invalid byte order")
            sectorSize = when (u16(header, SECTOR_SHIFT_OFFSET)) {
                9 -> 512
                12 -> 4096
                else -> throw IOException("unsupported sector size")
            }
            if (u16(header, MINI_SECTOR_SHIFT_OFFSET) != MINI_SECTOR_SHIFT) {
                throw IOException("unsupported mini sector size")
            }
            miniCutoff = u32(header, MINI_CUTOFF_OFFSET)
            totalSectors = ((fileLength - HEADER_SIZE) / sectorSize).toInt()
            if (totalSectors < 1) throw IOException("compound file has no sectors")

            val perFat = sectorSize / 4
            val numFat = u32(header, NUM_FAT_SECTORS_OFFSET)
            val maxFat = (totalSectors + perFat - 1) / perFat + 1
            if (numFat < 1 || numFat > maxFat) throw IOException("invalid FAT size")

            // DIFAT: 109 pointers in the header, then DIFAT sectors whose last slot points on.
            val pointers = ArrayList<Int>(DIFAT_HEADER_ENTRIES)
            for (i in 0 until DIFAT_HEADER_ENTRIES) pointers += difatPointer(header, DIFAT_OFFSET + i * 4)
            val perDifat = perFat - 1
            val numDifat = u32(header, NUM_DIFAT_SECTORS_OFFSET)
            if (numDifat > (totalSectors + perDifat - 1) / perDifat + 1) throw IOException("invalid DIFAT size")
            var next = u32(header, FIRST_DIFAT_SECTOR_OFFSET)
            var read = 0L
            while (read < numDifat) {
                if (next >= totalSectors) throw IOException("invalid DIFAT chain")
                val sector = raw(sectorOffset(next.toInt()), sectorSize)
                for (i in 0 until perDifat) pointers += difatPointer(sector, i * 4)
                next = u32(sector, perDifat * 4)
                read++
            }
            val fatSectors = pointers.filter { it >= 0 }
            if (fatSectors.size < numFat) throw IOException("FAT pointers missing")
            fat = join(rawMany(fatSectors.take(numFat.toInt()).map(::sectorOffset), sectorSize))

            val firstDir = u32(header, FIRST_DIR_SECTOR_OFFSET)
            if (firstDir == ENDOFCHAIN.toLong()) throw IOException("compound file has no directory")
            if (firstDir >= totalSectors) throw IOException("invalid directory sector")
            val dirSectors = chain(firstDir.toInt(), fat, totalSectors, "directory")
            if (dirSectors.size > MAX_DIR_SECTORS) throw IOException("directory too large")
            entries = rawMany(dirSectors.map(::sectorOffset), sectorSize).flatMap(::parseEntries)
            root = entries.firstOrNull { it.type == ENTRY_ROOT }
            firstMiniFat = u32(header, FIRST_MINI_FAT_SECTOR_OFFSET).toInt()
        }

        fun hasStream(name: String): Boolean =
            entries.any { it.type == ENTRY_STREAM && it.name.equals(name, ignoreCase = true) }

        fun read(name: String, offset: Long, length: Int): ByteArray {
            val entry = entries.firstOrNull { it.type == ENTRY_STREAM && it.name.equals(name, ignoreCase = true) }
                ?: throw IOException("stream not found: $name")
            if (offset < 0 || length < 0 || offset > entry.size || length > entry.size - offset) {
                throw IOException("read out of bounds: $name")
            }
            if (length == 0) return ByteArray(0)
            val chunks = if (entry.size < miniCutoff) {
                miniChunks(entry, offset, length)
            } else {
                regularChunks(entry, offset, length)
            }
            val out = ByteArray(length)
            // One open for the whole read; chunks sorted by file offset so the stream only moves
            // forward even when the FAT chain itself jumps backwards.
            val sorted = chunks.sortedBy { it.fileOffset }
            openStream().use { input ->
                var pos = 0L
                for (chunk in sorted) {
                    skipFully(input, chunk.fileOffset - pos)
                    readFully(input, out, chunk.dest, chunk.length)
                    pos = chunk.fileOffset + chunk.length
                }
            }
            return out
        }

        private fun regularChunks(entry: Entry, offset: Long, length: Int): List<Chunk> {
            val sectors = chain(entry.start, fat, totalSectors, "stream ${entry.name}")
            if (offset + length > sectors.size.toLong() * sectorSize) {
                throw IOException("stream chain too short: ${entry.name}")
            }
            val chunks = ArrayList<Chunk>()
            var sectorIndex = (offset / sectorSize).toInt()
            var within = (offset % sectorSize).toInt()
            var dest = 0
            while (dest < length) {
                val fileOffset = sectorOffset(sectors[sectorIndex]) + within
                val n = minOf(length - dest, sectorSize - within)
                chunks += Chunk(fileOffset, dest, n)
                dest += n
                within = 0
                sectorIndex++
            }
            return chunks
        }

        private fun miniChunks(entry: Entry, offset: Long, length: Int): List<Chunk> {
            val table = miniFat ?: throw IOException("mini FAT missing")
            val sectors = chain(entry.start, table, table.size, "mini stream ${entry.name}")
            if (offset + length > sectors.size.toLong() * MINI_SECTOR_SIZE) {
                throw IOException("mini stream chain too short: ${entry.name}")
            }
            val rootSectors = miniRoot
            val chunks = ArrayList<Chunk>()
            var miniIndex = (offset / MINI_SECTOR_SIZE).toInt()
            var within = (offset % MINI_SECTOR_SIZE).toInt()
            var dest = 0
            while (dest < length) {
                val rootOffset = sectors[miniIndex].toLong() * MINI_SECTOR_SIZE
                val rootIndex = (rootOffset / sectorSize).toInt()
                if (rootIndex >= rootSectors.size) throw IOException("mini stream beyond root stream")
                val fileOffset = sectorOffset(rootSectors[rootIndex]) + rootOffset % sectorSize + within
                val n = minOf(length - dest, MINI_SECTOR_SIZE - within)
                chunks += Chunk(fileOffset, dest, n)
                dest += n
                within = 0
                miniIndex++
            }
            return chunks
        }

        /** Walks a FAT chain with a step guard: a cycle cannot exceed the table without repeating. */
        private fun chain(start: Int, table: IntArray, maxSectors: Int, what: String): List<Int> {
            if (start == ENDOFCHAIN) return emptyList()
            val out = ArrayList<Int>()
            var s = start
            while (s != ENDOFCHAIN) {
                if (s < 0 || s >= maxSectors || s >= table.size) throw IOException("invalid sector in $what")
                out += s
                if (out.size > table.size) throw IOException("sector chain cycle in $what")
                s = table[s]
            }
            return out
        }

        private fun difatPointer(b: ByteArray, off: Int): Int {
            val v = u32(b, off)
            if (v == FREESECT) return -1
            if (v >= totalSectors) throw IOException("invalid DIFAT entry")
            return v.toInt()
        }

        private fun parseEntries(sector: ByteArray): List<Entry> {
            val out = ArrayList<Entry>(sector.size / ENTRY_SIZE)
            var off = 0
            while (off + ENTRY_SIZE <= sector.size) {
                val type = sector[off + ENTRY_TYPE_OFFSET].toInt() and 0xFF
                if (type == ENTRY_STREAM || type == ENTRY_ROOT) {
                    val nameLen = u16(sector, off + ENTRY_NAME_LENGTH_OFFSET)
                    // nameLen counts the terminator; a broken length must not read past the entry.
                    val nameBytes = (nameLen - 2).coerceIn(0, 64) and 1.inv()
                    val name = if (nameBytes > 0) String(sector, off, nameBytes, Charsets.UTF_16LE) else ""
                    val start = i32(sector, off + ENTRY_START_OFFSET)
                    val size = (i32(sector, off + ENTRY_SIZE_OFFSET).toLong() and 0xFFFFFFFFL) or
                        ((i32(sector, off + ENTRY_SIZE_OFFSET + 4).toLong() and 0xFFFFFFFFL) shl 32)
                    out += Entry(name, type, start, size)
                }
                off += ENTRY_SIZE
            }
            return out
        }

        private fun join(sectors: List<ByteArray>): IntArray {
            val per = sectorSize / 4
            val out = IntArray(sectors.size * per)
            var at = 0
            for (s in sectors) for (i in 0 until per) out[at++] = i32(s, i * 4)
            return out
        }

        private fun sectorOffset(sector: Int): Long = (sector.toLong() + 1) * sectorSize

        private fun raw(offset: Long, length: Int): ByteArray = rawMany(listOf(offset), length)[0]

        /** Several absolute reads through one open, sorted by offset so the stream only moves forward. */
        private fun rawMany(offsets: List<Long>, length: Int): List<ByteArray> {
            if (offsets.isEmpty()) return emptyList()
            val order = offsets.indices.sortedBy { offsets[it] }
            val out = Array(offsets.size) { ByteArray(length) }
            openStream().use { input ->
                var pos = 0L
                for (idx in order) {
                    val off = offsets[idx]
                    if (off < 0 || off + length > fileLength) throw IOException("read past end of file")
                    skipFully(input, off - pos)
                    readFully(input, out[idx], 0, length)
                    pos = off + length
                }
            }
            return out.toList()
        }
    }
}

/** Sequential byte count of the whole file: the only length a generic [InputStream] reports exactly. */
private fun probeLength(openStream: () -> InputStream): Long {
    openStream().use { input ->
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_FILE_BYTES) throw IOException("document is too large")
        }
        return total
    }
}

private fun skipFully(input: InputStream, n: Long) {
    var remaining = n
    while (remaining > 0) {
        val skipped = input.skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else if (input.read() == -1) {
            throw IOException("unexpected end of stream")
        } else {
            remaining--
        }
    }
}

private fun readFully(input: InputStream, dest: ByteArray, off: Int, len: Int) {
    var read = 0
    while (read < len) {
        val n = input.read(dest, off + read, len - read)
        if (n == -1) throw IOException("truncated file")
        read += n
    }
}

private fun u16(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

private fun u32(b: ByteArray, off: Int): Long =
    (b[off].toLong() and 0xFF) or
        ((b[off + 1].toLong() and 0xFF) shl 8) or
        ((b[off + 2].toLong() and 0xFF) shl 16) or
        ((b[off + 3].toLong() and 0xFF) shl 24)

private fun i32(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        (b[off + 3].toInt() shl 24)

/** FcCompressed 8-bit text: cp1252-style specials map to their Unicode characters, the rest are Latin-1. */
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

private const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024
private const val MAX_CLX_BYTES = 16 * 1024 * 1024
private const val MAX_DIR_SECTORS = 1024
private const val MAX_PRC_GRPPRL = 0x3FA2

private const val HEADER_SIZE = 512
private const val ENTRY_SIZE = 128
private const val DIFAT_HEADER_ENTRIES = 109
private const val MINI_SECTOR_SIZE = 64
private const val MINI_SECTOR_SHIFT = 6
private const val BYTE_ORDER_LE = 0xFFFE

private val OLE_SIGNATURE = intArrayOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)

private const val ENDOFCHAIN = -2 // 0xFFFFFFFE
private const val FREESECT = 0xFFFFFFFFL

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

// Header field offsets (CFB spec, fixed across v3/v4).
private const val BYTE_ORDER_OFFSET = 0x1C
private const val SECTOR_SHIFT_OFFSET = 0x1E
private const val MINI_SECTOR_SHIFT_OFFSET = 0x20
private const val NUM_FAT_SECTORS_OFFSET = 0x2C
private const val FIRST_DIR_SECTOR_OFFSET = 0x30
private const val MINI_CUTOFF_OFFSET = 0x38
private const val FIRST_MINI_FAT_SECTOR_OFFSET = 0x3C
private const val NUM_DIFAT_SECTORS_OFFSET = 0x48
private const val FIRST_DIFAT_SECTOR_OFFSET = 0x44
private const val DIFAT_OFFSET = 0x4C

// Directory entry field offsets.
private const val ENTRY_NAME_LENGTH_OFFSET = 0x40
private const val ENTRY_TYPE_OFFSET = 0x42
private const val ENTRY_START_OFFSET = 0x74
private const val ENTRY_SIZE_OFFSET = 0x78
private const val ENTRY_STREAM = 2
private const val ENTRY_ROOT = 5
