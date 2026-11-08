package com.papyrus.app.viewer

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * What a walk over one ZIP may cost, whoever reads it. The defaults sit far above a real book or document
 * (a long novel is a few MB of XHTML in a few hundred entries) and far below what would hurt.
 */
data class ZipLimits(
    val maxEntries: Int = 10_000,
    /** One entry's inflated size, and so what a single bomb entry can cost in time. */
    val maxEntryBytes: Long = 64L * 1024 * 1024,
    val maxTotalBytes: Long = 512L * 1024 * 1024,
    /** Inflated bytes per compressed byte, judged once an entry is past [ratioFloorBytes]. */
    val maxRatio: Int = 100,
    val ratioFloorBytes: Long = 1024L * 1024,
)

/**
 * A [ZipInputStream] that refuses an archive built to hurt its reader: too many entries, an entry or total
 * that inflates past its cap, or one that compresses implausibly well (a bomb). Every byte the ZIP layer
 * inflates is counted as it comes out, the entries a reader skips included, since the next [getNextEntry]
 * inflates the rest of the one before; the sizes an entry declares are never trusted, since a bomb declares
 * what it likes. A breach throws what [fail] makes from its reason.
 */
internal class BoundedZipInputStream private constructor(
    private val counting: CountingInputStream,
    private val limits: ZipLimits,
    private val fail: (String) -> IOException,
) : ZipInputStream(counting) {

    constructor(
        source: InputStream,
        limits: ZipLimits = ZipLimits(),
        fail: (String) -> IOException = { reason -> IOException(reason) },
    ) : this(CountingInputStream(source), limits, fail)

    private var entries = 0
    private var entryBytes = 0L
    private var entryFrom = 0L
    private var totalBytes = 0L

    override fun getNextEntry(): ZipEntry? {
        // Finishing the previous entry is read through this class too, so it is charged to that entry.
        val entry = super.getNextEntry() ?: return null
        if (++entries > limits.maxEntries) throw fail("too many entries")
        entryBytes = 0L
        entryFrom = counting.count
        return entry
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) charge(n)
        return n
    }

    private fun charge(n: Int) {
        entryBytes += n
        totalBytes += n
        if (entryBytes > limits.maxEntryBytes || totalBytes > limits.maxTotalBytes) throw fail("too large")
        if (entryBytes >= limits.ratioFloorBytes) {
            val compressed = (counting.count - entryFrom).coerceAtLeast(1L)
            if (entryBytes / compressed > limits.maxRatio) throw fail("implausible compression")
        }
    }
}

/** Counts what the ZIP layer pulls from the file, which is the compressed size of what it has inflated. */
internal class CountingInputStream(source: InputStream) : FilterInputStream(source) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

    override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) count += it }
}
