package io.github.pixelrick420.papyrus.viewer

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.BitSet

// Read-only and bounds-checked: a truncated or cyclic file throws instead of looping or over-allocating.
internal class CompoundFile(private val openStream: () -> InputStream, knownLength: Long? = null) {
    internal class Entry(
        val name: String,
        val type: Int,
        val start: Int,
        val size: Long,
        val left: Int,
        val right: Int,
        val child: Int,
    )
    private class Chunk(val fileOffset: Long, val dest: Int, val length: Int)

    private val fileLength: Long = knownLength?.takeIf { it in HEADER_SIZE..MAX_FILE_BYTES } ?: probeLength(openStream)
    private val cachedStreams = HashMap<String, ByteArray>()
    private val chainCache = HashMap<Int, IntArray>()
    private val miniChainCache = HashMap<Int, IntArray>()

    private val sectorSize: Int
    private val totalSectors: Int
    private val fat: IntArray
    private val entries: List<Entry>
    private val miniCutoff: Long
    private val firstMiniFat: Int
    private val root: Entry?

    private val miniFat: IntArray? by lazy {
        if (firstMiniFat == ENDOFCHAIN) return@lazy null
        val per = sectorSize / 4
        val sectors = chain(firstMiniFat, fat, totalSectors, "mini FAT", minOf(totalSectors, MAX_TABLE_ENTRIES / per))
        readTable(sectors, "mini FAT")
    }

    private val miniRoot: IntArray by lazy {
        val r = root ?: throw IOException("missing root entry")
        if (r.start == ENDOFCHAIN || r.size == 0L) throw IOException("missing mini stream")
        if (r.size > fileLength) throw IOException("mini stream too large")
        val needed = ((r.size + sectorSize - 1) / sectorSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        chain(r.start, fat, totalSectors, "mini stream", minOf(needed, totalSectors))
    }

    init {
        if (fileLength < HEADER_SIZE) throw IOException("truncated compound file")
        val header = readAt(0, HEADER_SIZE)
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
        totalSectors = ((fileLength - maxOf(HEADER_SIZE, sectorSize)) / sectorSize).toInt()
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
            val sector = readAt(sectorOffset(next.toInt()), sectorSize)
            for (i in 0 until perDifat) pointers += difatPointer(sector, i * 4)
            next = u32(sector, perDifat * 4)
            read++
        }
        val valid = pointers.filter { it >= 0 }
        if (valid.size < numFat) throw IOException("FAT pointers missing")
        val fatSectors = IntArray(numFat.toInt()) { valid[it] }
        fat = readTable(fatSectors, "FAT")

        val firstDir = u32(header, FIRST_DIR_SECTOR_OFFSET)
        if (firstDir == ENDOFCHAIN.toLong()) throw IOException("compound file has no directory")
        if (firstDir >= totalSectors) throw IOException("invalid directory sector")
        val dirSectors = chain(firstDir.toInt(), fat, totalSectors, "directory")
        if (dirSectors.size > MAX_DIR_SECTORS) throw IOException("directory too large")
        val parsed = ArrayList<Entry>(dirSectors.size * (sectorSize / ENTRY_SIZE))
        readSectors(dirSectors) { buffer, off -> parseEntries(buffer, off, sectorSize, parsed) }
        entries = parsed
        root = entries.firstOrNull { it.type == ENTRY_ROOT }
        firstMiniFat = u32(header, FIRST_MINI_FAT_SECTOR_OFFSET).toInt()
    }

    fun hasStream(path: String): Boolean = resolve(path)?.type == ENTRY_STREAM

    fun streamSize(path: String): Long? = resolve(path)?.takeIf { it.type == ENTRY_STREAM }?.size

    fun cacheStream(path: String, maxBytes: Int): Boolean {
        val key = path.lowercase()
        if (key in cachedStreams) return true
        val size = streamSize(path) ?: return false
        if (size <= 0 || size > maxBytes) return false
        cachedStreams[key] = read(path, 0, size.toInt())
        return true
    }

    fun read(path: String, offset: Long, length: Int): ByteArray {
        val entry = resolve(path)?.takeIf { it.type == ENTRY_STREAM }
            ?: throw IOException("stream not found: $path")
        checkBounds(entry, path, offset, length)
        if (length == 0) return ByteArray(0)
        cachedStreams[path.lowercase()]?.let { cached ->
            return cached.copyOfRange(offset.toInt(), offset.toInt() + length)
        }
        val chunks = chunksFor(entry, offset, length)
        val out = ByteArray(length)
        // One open for the whole read; chunks sorted by file offset so the stream only moves
        // forward even when the FAT chain itself jumps backwards.
        openStream().use { input -> copyChunks(chunks, out, input, 0L) }
        return out
    }

    /** Forward-only stream reader: one open handle serves a whole sequential decryption pass. */
    inner class Reader internal constructor(private val path: String, private val entry: Entry) : Closeable {
        private val cached = cachedStreams[path.lowercase()]
        private val input: InputStream? = if (cached == null) openStream() else null
        private var streamPosition = 0L
        private var filePosition = 0L
        private var closed = false

        fun read(offset: Long, length: Int): ByteArray {
            if (closed) throw IOException("reader is closed")
            checkBounds(entry, path, offset, length)
            if (length == 0) return ByteArray(0)
            cached?.let { return it.copyOfRange(offset.toInt(), offset.toInt() + length) }
            if (offset < streamPosition) throw IOException("reader requires forward-only reads")
            val out = ByteArray(length)
            val stream = input ?: throw IOException("reader is closed")
            filePosition = copyChunks(chunksFor(entry, offset, length), out, stream, filePosition)
            streamPosition = offset + length
            return out
        }

        override fun close() {
            if (closed) return
            closed = true
            input?.close()
        }
    }

    fun openReader(path: String): Reader {
        val entry = resolve(path)?.takeIf { it.type == ENTRY_STREAM }
            ?: throw IOException("stream not found: $path")
        return Reader(path, entry)
    }

    private fun checkBounds(entry: Entry, path: String, offset: Long, length: Int) {
        if (offset < 0 || length < 0 || offset > entry.size || length > entry.size - offset) {
            throw IOException("read out of bounds: $path")
        }
    }

    private fun copyChunks(chunks: List<Chunk>, out: ByteArray, input: InputStream, from: Long): Long {
        var position = from
        for (chunk in chunks.sortedBy { it.fileOffset }) {
            if (chunk.fileOffset < position) throw IOException("overlapping chunks")
            skipFully(input, chunk.fileOffset - position)
            readFully(input, out, chunk.dest, chunk.length)
            position = chunk.fileOffset + chunk.length
        }
        return position
    }

    private fun chunksFor(entry: Entry, offset: Long, length: Int): List<Chunk> =
        if (entry.size < miniCutoff) miniChunks(entry, offset, length) else regularChunks(entry, offset, length)

    private fun regularChain(start: Int): IntArray =
        chainCache.getOrPut(start) { chain(start, fat, totalSectors, "stream chain") }

    private fun miniChain(start: Int): IntArray {
        val table = miniFat ?: throw IOException("mini FAT missing")
        return miniChainCache.getOrPut(start) { chain(start, table, table.size, "mini chain") }
    }

    private fun regularChunks(entry: Entry, offset: Long, length: Int): List<Chunk> {
        val needed = ((offset + length + sectorSize - 1) / sectorSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val sectors = regularChain(entry.start)
        if (sectors.size < needed) throw IOException("stream chain too short: ${entry.name}")
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
        val needed = ((offset + length + MINI_SECTOR_SIZE - 1) / MINI_SECTOR_SIZE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val sectors = miniChain(entry.start)
        if (sectors.size < needed) throw IOException("mini stream chain too short: ${entry.name}")
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

    private fun resolve(path: String): Entry? {
        val parts = path.split('\\', '/').filter { it.isNotEmpty() }
        var child = root?.child ?: NO_STREAM
        var found: Entry? = null
        for ((index, part) in parts.withIndex()) {
            found = findSibling(child, part) ?: return null
            if (index < parts.lastIndex) {
                if (found.type != ENTRY_STORAGE) return null
                child = found.child
            }
        }
        return found
    }

    private fun findSibling(start: Int, name: String): Entry? {
        val seen = BitSet(entries.size)
        var node = start
        while (node >= 0 && node < entries.size) {
            if (seen[node]) throw IOException("directory cycle")
            seen[node] = true
            val entry = entries[node]
            val lengthOrder = name.length.compareTo(entry.name.length)
            val order = if (lengthOrder == 0) name.compareTo(entry.name, ignoreCase = true) else lengthOrder
            node = when {
                order < 0 -> entry.left
                order > 0 -> entry.right
                else -> return entry
            }
        }
        return null
    }

    // Bounded walk with a seen-set so a cyclic FAT throws instead of spinning.
    private fun chain(start: Int, table: IntArray, maxSectors: Int, what: String, limit: Int = Int.MAX_VALUE): IntArray {
        if (start == ENDOFCHAIN || limit == 0) return IntArray(0)
        val cap = minOf(maxSectors, table.size, limit)
        if (cap <= 0) return IntArray(0)
        var out = IntArray(minOf(cap, 64))
        val seen = BitSet(table.size)
        var size = 0
        var s = start
        while (s != ENDOFCHAIN && size < cap) {
            if (s < 0 || s >= maxSectors || s >= table.size) throw IOException("invalid sector in $what")
            if (seen[s]) throw IOException("sector chain cycle in $what")
            seen[s] = true
            if (size == out.size) out = out.copyOf(minOf(cap, size * 2))
            out[size++] = s
            s = table[s]
        }
        return out.copyOf(size)
    }

    private fun difatPointer(b: ByteArray, off: Int): Int {
        val v = u32(b, off)
        if (v == FREESECT) return -1
        if (v >= totalSectors) throw IOException("invalid DIFAT entry")
        return v.toInt()
    }

    private fun parseEntries(sector: ByteArray, off: Int, length: Int, out: MutableList<Entry>) {
        var at = off
        val end = off + length
        while (at + ENTRY_SIZE <= end) {
            val type = sector[at + ENTRY_TYPE_OFFSET].toInt() and 0xFF
            if (type == ENTRY_STREAM || type == ENTRY_ROOT || type == ENTRY_STORAGE) {
                val nameLen = u16(sector, at + ENTRY_NAME_LENGTH_OFFSET)
                // nameLen counts the terminator; a broken length must not read past the entry.
                val nameBytes = (nameLen - 2).coerceIn(0, 64) and 1.inv()
                val name = if (nameBytes > 0) String(sector, at, nameBytes, Charsets.UTF_16LE) else ""
                val start = i32(sector, at + ENTRY_START_OFFSET)
                val size = (i32(sector, at + ENTRY_SIZE_OFFSET).toLong() and 0xFFFFFFFFL) or
                    ((i32(sector, at + ENTRY_SIZE_OFFSET + 4).toLong() and 0xFFFFFFFFL) shl 32)
                out += Entry(
                    name,
                    type,
                    start,
                    size,
                    i32(sector, at + ENTRY_LEFT_OFFSET),
                    i32(sector, at + ENTRY_RIGHT_OFFSET),
                    i32(sector, at + ENTRY_CHILD_OFFSET),
                )
            }
            at += ENTRY_SIZE
        }
    }

    private fun readTable(sectors: IntArray, what: String): IntArray {
        val per = sectorSize / 4
        if (sectors.size.toLong() * per > MAX_TABLE_ENTRIES) throw IOException("$what table too large")
        val out = IntArray(sectors.size * per)
        var at = 0
        readSectors(sectors) { buffer, off ->
            for (i in 0 until per) out[at++] = i32(buffer, off + i * 4)
        }
        return out
    }

    private fun sectorOffset(sector: Int): Long = (sector.toLong() + 1) * sectorSize

    private fun readAt(offset: Long, length: Int): ByteArray {
        if (offset < 0 || offset + length > fileLength) throw IOException("read past end of file")
        val out = ByteArray(length)
        openStream().use { input ->
            skipFully(input, offset)
            readFully(input, out, 0, length)
        }
        return out
    }

    // Bounded batches keep allocation flat; visit consumes each buffer before the next batch.
    private fun readSectors(sectors: IntArray, visit: (ByteArray, Int) -> Unit) {
        var cursor = 0
        while (cursor < sectors.size) {
            val end = minOf(cursor + MAX_SECTOR_BATCH, sectors.size)
            val order = (cursor until end).sortedBy { sectorOffset(sectors[it]) }
            val buffer = ByteArray((end - cursor) * sectorSize)
            openStream().use { input ->
                var pos = 0L
                for (i in order) {
                    val off = sectorOffset(sectors[i])
                    if (off < 0 || off + sectorSize > fileLength) throw IOException("read past end of file")
                    if (off < pos) throw IOException("overlapping sectors")
                    skipFully(input, off - pos)
                    readFully(input, buffer, (i - cursor) * sectorSize, sectorSize)
                    pos = off + sectorSize
                }
            }
            for (i in cursor until end) visit(buffer, (i - cursor) * sectorSize)
            cursor = end
        }
    }
}

// Sequential byte count: the only length a generic InputStream reports exactly.
internal fun probeLength(openStream: () -> InputStream): Long {
    openStream().use { input ->
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        var stalled = 0
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0 && ++stalled >= MAX_STALLED_READS) throw IOException("stream made no progress")
            if (n > 0) stalled = 0
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
    var stalled = 0
    while (read < len) {
        val n = input.read(dest, off + read, len - read)
        if (n == -1) throw IOException("truncated file")
        if (n == 0 && ++stalled >= MAX_STALLED_READS) throw IOException("stream made no progress")
        if (n > 0) stalled = 0
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

internal const val MAX_STALLED_READS = 16

private const val MAX_FILE_BYTES = 4L * 1024 * 1024 * 1024
private const val MAX_DIR_SECTORS = 1024
private const val MAX_TABLE_ENTRIES = 16 * 1024 * 1024
private const val MAX_SECTOR_BATCH = 64

private const val HEADER_SIZE = 512
private const val ENTRY_SIZE = 128
private const val DIFAT_HEADER_ENTRIES = 109
private const val MINI_SECTOR_SIZE = 64
private const val MINI_SECTOR_SHIFT = 6
private const val BYTE_ORDER_LE = 0xFFFE

private val OLE_SIGNATURE = intArrayOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)

private const val ENDOFCHAIN = -2 // 0xFFFFFFFE
private const val FREESECT = 0xFFFFFFFFL

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

private const val ENTRY_NAME_LENGTH_OFFSET = 0x40
private const val ENTRY_TYPE_OFFSET = 0x42
private const val ENTRY_LEFT_OFFSET = 0x44
private const val ENTRY_RIGHT_OFFSET = 0x48
private const val ENTRY_CHILD_OFFSET = 0x4C
private const val ENTRY_START_OFFSET = 0x74
private const val ENTRY_SIZE_OFFSET = 0x78
private const val ENTRY_STORAGE = 1
private const val ENTRY_STREAM = 2
private const val ENTRY_ROOT = 5
private const val NO_STREAM = -1
