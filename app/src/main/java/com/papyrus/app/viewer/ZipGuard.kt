package com.papyrus.app.viewer

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class ZipLimits(
    val maxEntries: Int = 10_000,
    val maxEntryBytes: Long = 64L * 1024 * 1024,
    val maxTotalBytes: Long = 512L * 1024 * 1024,
    val maxRatio: Int = 100,
    val ratioFloorBytes: Long = 1024L * 1024,
)

// Every byte the ZIP layer inflates is counted as it comes out, skipped entries included, since the next getNextEntry inflates the rest of the one before; declared sizes are never trusted.
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

internal class CountingInputStream(source: InputStream) : FilterInputStream(source) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

    override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) count += it }
}
