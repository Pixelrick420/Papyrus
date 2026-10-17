package com.papyrus.app.viewer

import java.io.IOException
import java.io.InputStream

/**
 * Minimal OLE2/CFB reader shared by the Word `.doc` extractor and the encrypted-OOXML unwrapper.
 * Read-only and bounds-checked: a truncated or cyclic file throws instead of looping or over-allocating.
 */
internal class CompoundFile(private val openStream: () -> InputStream, private val fileLength: Long) {
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

    /** Declared size of a stream, or null when there is no such stream. */
    fun streamSize(name: String): Long? =
        entries.firstOrNull { it.type == ENTRY_STREAM && it.name.equals(name, ignoreCase = true) }?.size

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

/** Sequential byte count of the whole file: the only length a generic [InputStream] reports exactly. */
internal fun probeLength(openStream: () -> InputStream): Long {
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

internal fun skipFully(input: InputStream, n: Long) {
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

internal fun readFully(input: InputStream, dest: ByteArray, off: Int, len: Int) {
    var read = 0
    while (read < len) {
        val n = input.read(dest, off + read, len - read)
        if (n == -1) throw IOException("truncated file")
        read += n
    }
}

internal fun u16(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

internal fun u32(b: ByteArray, off: Int): Long =
    (b[off].toLong() and 0xFF) or
        ((b[off + 1].toLong() and 0xFF) shl 8) or
        ((b[off + 2].toLong() and 0xFF) shl 16) or
        ((b[off + 3].toLong() and 0xFF) shl 24)

internal fun i32(b: ByteArray, off: Int): Int =
    (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        (b[off + 3].toInt() shl 24)

private const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024
private const val MAX_DIR_SECTORS = 1024

private const val HEADER_SIZE = 512
private const val ENTRY_SIZE = 128
private const val DIFAT_HEADER_ENTRIES = 109
private const val MINI_SECTOR_SIZE = 64
private const val MINI_SECTOR_SHIFT = 6
private const val BYTE_ORDER_LE = 0xFFFE

private val OLE_SIGNATURE = intArrayOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)

private const val ENDOFCHAIN = -2 // 0xFFFFFFFE
private const val FREESECT = 0xFFFFFFFFL

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
