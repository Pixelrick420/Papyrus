package com.papyrus.app.viewer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * In-memory Word 97-2003 builders: a `.doc` is binary, so a fixture cannot be reviewed in a diff.
 * [doc] assembles the FIB, piece table and text; [compound] wraps any streams in an OLE2 container
 * whose FAT serves both the mini and the regular stream path, so tests exercise whichever a real
 * document of that size would take.
 */
internal object DocFixtures {

    fun streamFactory(bytes: ByteArray): () -> java.io.InputStream = { ByteArrayInputStream(bytes) }

    /** One piece-table entry: its own character count and byte encoding. */
    class Piece(val text: String, val compressed: Boolean = true)

    /**
     * A complete single-table-stream `.doc`. Corruption knobs cover every rejection the extractor
     * performs; [pieces] replaces the single-text-piece default for mixed-encoding tests.
     */
    fun doc(
        text: String = "Hello",
        pieces: List<Piece>? = null,
        ccpText: Int? = null,
        wIdent: Int = 0xA5EC,
        nFib: Int = 0x00C1,
        flags: Int = 0,
        tableStream: String = "0Table",
        omitTable: Boolean = false,
        omitWordDocument: Boolean = false,
        fcClx: Int = 0,
        lcbClx: Int? = null,
        clxOverride: ByteArray? = null,
        prcPrefix: Boolean = false,
    ): ByteArray {
        val specs = pieces ?: listOf(Piece(text))
        var offset = FIB_SIZE
        val laid = specs.map { spec ->
            val bytes = encode(spec)
            val fc = if (spec.compressed) (offset.toLong() shl 1) or FC_COMPRESSED else offset.toLong()
            offset += bytes.size
            LaidOut(bytes, fc, spec.text.length)
        }
        val pcdt = clxOverride ?: pcdt(laid)
        val clx = (if (prcPrefix) prcChunk() else ByteArray(0)) + pcdt
        val fib = buildFib(
            ccpText = ccpText ?: specs.sumOf { it.text.length },
            wIdent = wIdent,
            nFib = nFib,
            flags = flags,
            fcClx = fcClx,
            lcbClx = lcbClx ?: clx.size,
        )
        val word = ByteArrayOutputStream(fib.size + offset)
            .also { out ->
                out.write(fib)
                laid.forEach { out.write(it.bytes) }
            }
            .toByteArray()
        val streams = LinkedHashMap<String, ByteArray>()
        if (!omitWordDocument) streams[WORD_DOCUMENT] = word
        if (!omitTable) streams[tableStream] = clx
        return compound(streams)
    }

    /** Wraps a Pcdt in a Prc grpprl chunk, the form the Clx walk must skip over. */
    private fun prcChunk(): ByteArray {
        val grpprl = byteArrayOf(0x01, 0x02)
        return byteArrayOf(CLXT_PRC.toByte(), grpprl.size.toByte(), 0x00) + grpprl
    }

    /** Little-endian u32 bytes, for tests that hand-build a malformed CLX. */
    fun u32Bytes(value: Long): ByteArray = ByteArray(4) { i -> ((value shr (i * 8)) and 0xFF).toByte() }

    /** File offset of the first FAT sector, whose entry 0 the chain-cycle test overwrites. */
    fun firstFatSectorOffset(bytes: ByteArray): Int {
        val id = (bytes[0x4C].toLong() and 0xFF) or
            ((bytes[0x4D].toLong() and 0xFF) shl 8) or
            ((bytes[0x4E].toLong() and 0xFF) shl 16) or
            ((bytes[0x4F].toLong() and 0xFF) shl 24)
        return ((id + 1) * 512).toInt()
    }

    private class LaidOut(val bytes: ByteArray, val fc: Long, val chars: Int)

    private fun encode(piece: Piece): ByteArray =
        if (piece.compressed) {
            ByteArray(piece.text.length) { compressedByte(piece.text[it]).toByte() }
        } else {
            piece.text.toByteArray(Charsets.UTF_16LE)
        }

    /**
     * Inverse of the extractor's FcCompressed mapping. Unencodable characters fail the fixture
     * loudly: a compressed piece never carried them in the first place.
     */
    private fun compressedByte(c: Char): Int {
        SPECIAL_TO_BYTE[c]?.let { return it }
        val code = c.code
        require(code <= 0xFF && (code < 0x80 || code > 0x9F || code !in SPECIAL_TO_BYTE.values)) {
            "U+${code.toString(16).uppercase()} has no compressed encoding"
        }
        return code
    }

    private fun pcdt(laid: List<LaidOut>): ByteArray {
        // The Pcdt's lcb counts only the PlcPcd itself: (n+1) CPs plus n PCDs, no length prefix.
        val plcLen = (laid.size + 1) * 4 + laid.size * 8
        val out = ByteArrayOutputStream()
        out.writeU8(CLXT_PCDT)
        out.writeU32(plcLen.toLong())
        out.writeU32(0)
        var cp = 0
        for (piece in laid) {
            cp += piece.chars
            out.writeU32(cp.toLong())
        }
        for (piece in laid) {
            out.writeU16(0) // PCD flags
            out.writeU32(piece.fc)
            out.writeU16(0) // PCD prm
        }
        return out.toByteArray()
    }

    private fun buildFib(ccpText: Int, wIdent: Int, nFib: Int, flags: Int, fcClx: Int, lcbClx: Int): ByteArray {
        val fib = ByteArray(FIB_SIZE)
        putU16(fib, 0x00, wIdent)
        putU16(fib, 0x02, nFib)
        putU16(fib, 0x0A, flags)
        putU16(fib, 0x20, 14) // csw
        putU16(fib, 0x3E, 22) // cslw
        putU32(fib, 0x4C, ccpText.toLong()) // FibRgLw97[3], the ccpText slot
        putU16(fib, 0x98, 93) // cbRgFcLcb
        putU32(fib, 0x1A2, fcClx.toLong())
        putU32(fib, 0x1A6, lcbClx.toLong())
        return fib
    }

    private fun compound(streams: Map<String, ByteArray>): ByteArray {
        val sectorSize = 512
        val perFat = sectorSize / 4

        // Streams under the mini cutoff are packed into 64-byte mini sectors inside the root's
        // mini stream, each with its own mini-FAT chain ending at ENDOFCHAIN.
        val mini = ByteArrayOutputStream()
        val miniStart = LinkedHashMap<String, Int>()
        val miniChains = mutableListOf<Pair<Int, Int>>()
        val regular = LinkedHashMap<String, ByteArray>()
        for ((name, blob) in streams) {
            if (blob.size < MINI_CUTOFF) {
                val start = mini.size() / MINI_SECTOR_SIZE
                mini.write(blob)
                val pad = (MINI_SECTOR_SIZE - blob.size % MINI_SECTOR_SIZE) % MINI_SECTOR_SIZE
                repeat(pad) { mini.write(0) }
                miniStart[name] = start
                miniChains += start to (mini.size() / MINI_SECTOR_SIZE - start)
            } else {
                regular[name] = blob
            }
        }
        val miniCount = mini.size() / MINI_SECTOR_SIZE
        var miniBytes = mini.toByteArray()
        if (miniBytes.isNotEmpty() && miniBytes.size % sectorSize != 0) {
            miniBytes += ByteArray(sectorSize - miniBytes.size % sectorSize)
        }
        val miniFat = IntArray(miniCount) { FREESECT }
        for ((start, count) in miniChains) {
            for (i in 0 until count) miniFat[start + i] = if (i == count - 1) ENDOFCHAIN else start + i + 1
        }
        val miniFatBytes = padTo(intsToBytes(miniFat), sectorSize)

        val chains = LinkedHashMap<String, List<Int>>()
        var next = 0
        fun alloc(count: Int): List<Int> {
            val chain = (next until next + count).toList()
            next += count
            return chain
        }
        if (miniBytes.isNotEmpty()) chains[KEY_MINI_STREAM] = alloc(miniBytes.size / sectorSize)
        if (miniFatBytes.isNotEmpty()) chains[KEY_MINI_FAT] = alloc(miniFatBytes.size / sectorSize)
        for ((name, blob) in regular) chains[name] = alloc((blob.size + sectorSize - 1) / sectorSize)

        val starts = LinkedHashMap<String, Int>()
        miniStart.forEach { (name, start) -> starts[name] = start }
        for ((name, _) in regular) starts[name] = chains.getValue(name).first()
        val dirBytes = directory(
            streams = streams,
            starts = starts,
            rootStart = chains[KEY_MINI_STREAM]?.first() ?: ENDOFCHAIN,
            rootSize = miniBytes.size,
        )
        chains[KEY_DIR] = alloc((dirBytes.size + sectorSize - 1) / sectorSize)

        // Enough FAT sectors for every allocated sector including the FAT's own, plus one spare:
        // fat * (perFat - 1) >= dataSectors.
        val dataSectors = next
        val fatCount = (dataSectors + perFat - 2) / (perFat - 1)
        require(fatCount <= DIFAT_HEADER_ENTRIES) { "fixture too large for a header-only DIFAT" }
        val total = dataSectors + fatCount
        val fat = IntArray(total) { FREESECT }
        for (chain in chains.values) {
            for (i in chain.indices) fat[chain[i]] = if (i == chain.lastIndex) ENDOFCHAIN else chain[i + 1]
        }

        val out = ByteArray(HEADER_SIZE + total * sectorSize)
        val header = ByteArray(HEADER_SIZE)
        OLE_SIGNATURE.forEachIndexed { i, byte -> header[i] = byte.toByte() }
        putU16(header, 0x18, 0x003E) // minor version
        putU16(header, 0x1A, 0x0003) // major version 3
        putU16(header, 0x1C, 0xFFFE) // little-endian byte order
        putU16(header, 0x1E, 9) // sector shift: 512-byte sectors
        putU16(header, 0x20, 6) // mini sector shift: 64-byte mini sectors
        putU32(header, 0x2C, fatCount.toLong())
        putU32(header, 0x30, chains.getValue(KEY_DIR).first().toLong())
        putU32(header, 0x38, MINI_CUTOFF.toLong())
        putU32(header, 0x3C, (chains[KEY_MINI_FAT]?.first() ?: ENDOFCHAIN).toLong())
        putU32(header, 0x40, (chains[KEY_MINI_FAT]?.size ?: 0).toLong())
        putU32(header, 0x44, ENDOFCHAIN.toLong()) // no DIFAT sectors beyond the header's 109
        putU32(header, 0x48, 0)
        for (i in 0 until fatCount) putU32(header, 0x4C + i * 4, (dataSectors + i).toLong())
        for (i in fatCount until DIFAT_HEADER_ENTRIES) putU32(header, 0x4C + i * 4, FREESECT.toLong())
        header.copyInto(out)

        fun writeChain(key: String, data: ByteArray) {
            var at = 0
            for (sector in chains.getValue(key)) {
                val n = minOf(sectorSize, data.size - at)
                if (n > 0) {
                    System.arraycopy(data, at, out, HEADER_SIZE + sector * sectorSize, n)
                    at += n
                }
            }
        }
        chains[KEY_MINI_STREAM]?.let { writeChain(KEY_MINI_STREAM, miniBytes) }
        if (miniFatBytes.isNotEmpty()) writeChain(KEY_MINI_FAT, miniFatBytes)
        for ((name, blob) in regular) writeChain(name, blob)
        writeChain(KEY_DIR, dirBytes)
        for (f in 0 until fatCount) {
            val pos = HEADER_SIZE + (dataSectors + f) * sectorSize
            for (i in 0 until perFat) {
                val idx = f * perFat + i
                if (idx < total) putU32(out, pos + i * 4, fat[idx].toLong() and 0xFFFFFFFFL)
            }
        }
        return out
    }

    private fun directory(
        streams: Map<String, ByteArray>,
        starts: Map<String, Int>,
        rootStart: Int,
        rootSize: Int,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(dirEntry("Root Entry", ENTRY_ROOT, rootStart, rootSize))
        for ((name, blob) in streams) out.write(dirEntry(name, ENTRY_STREAM, starts.getValue(name), blob.size))
        return out.toByteArray()
    }

    private fun dirEntry(name: String, type: Int, start: Int, size: Int): ByteArray {
        val entry = ByteArray(ENTRY_SIZE)
        val nameBytes = name.toByteArray(Charsets.UTF_16LE)
        val copied = minOf(64, nameBytes.size)
        nameBytes.copyInto(entry, 0, 0, copied)
        putU16(entry, 0x40, copied + 2) // length including the UTF-16 terminator
        entry[0x42] = type.toByte()
        entry[0x43] = 1 // black
        putU32(entry, 0x74, start.toLong() and 0xFFFFFFFFL)
        putU32(entry, 0x78, size.toLong())
        return entry
    }

    private fun intsToBytes(values: IntArray): ByteArray {
        val bytes = ByteArray(values.size * 4)
        for (i in values.indices) putU32(bytes, i * 4, values[i].toLong() and 0xFFFFFFFFL)
        return bytes
    }

    private fun padTo(bytes: ByteArray, size: Int): ByteArray =
        if (bytes.size % size == 0) bytes else bytes + ByteArray(size - bytes.size % size)

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
    }

    private fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
        b[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun ByteArrayOutputStream.writeU8(v: Int) {
        write(v and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU16(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeU32(v: Long) {
        write((v and 0xFF).toInt())
        write(((v shr 8) and 0xFF).toInt())
        write(((v shr 16) and 0xFF).toInt())
        write(((v shr 24) and 0xFF).toInt())
    }

    private val SPECIAL_TO_BYTE = mapOf(
        '\u201A' to 0x82, '\u0192' to 0x83, '\u201E' to 0x84, '\u2026' to 0x85,
        '\u2020' to 0x86, '\u2021' to 0x87, '\u02C6' to 0x88, '\u2030' to 0x89,
        '\u0160' to 0x8A, '\u2039' to 0x8B, '\u0152' to 0x8C, '\u2018' to 0x91,
        '\u2019' to 0x92, '\u201C' to 0x93, '\u201D' to 0x94, '\u2022' to 0x95,
        '\u2013' to 0x96, '\u2014' to 0x97, '\u02DC' to 0x98, '\u2122' to 0x99,
        '\u0161' to 0x9A, '\u203A' to 0x9B, '\u0153' to 0x9C, '\u0178' to 0x9F,
    )

    private const val HEADER_SIZE = 512
    private const val ENTRY_SIZE = 128
    private const val FIB_SIZE = 0x382
    private const val MINI_CUTOFF = 4096
    private const val MINI_SECTOR_SIZE = 64
    private const val DIFAT_HEADER_ENTRIES = 109

    private const val ENDOFCHAIN = -2
    private const val FREESECT = -1

    private const val WORD_DOCUMENT = "WordDocument"
    private const val CLXT_PRC = 0x01
    private const val CLXT_PCDT = 0x02
    private const val FC_COMPRESSED = 0x40000000L
    private const val ENTRY_STREAM = 2
    private const val ENTRY_ROOT = 5

    private const val KEY_MINI_STREAM = "\$mini-stream"
    private const val KEY_MINI_FAT = "\$mini-fat"
    private const val KEY_DIR = "\$directory"

    private val OLE_SIGNATURE = intArrayOf(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1)
}
