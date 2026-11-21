package io.github.pixelrick420.papyrus.viewer

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

internal const val DEFAULT_MAX_SKIPPED_BYTES = 2L * 1024 * 1024 * 1024

data class ZipLimits(
    val maxEntries: Int = 10_000,
    val maxEntryBytes: Long = 64L * 1024 * 1024,
    val maxTotalBytes: Long = 512L * 1024 * 1024,
    val maxRatio: Int = 100,
    val ratioFloorBytes: Long = 1024L * 1024,
    val maxSkippedBytes: Long = DEFAULT_MAX_SKIPPED_BYTES,
)

// Bytes the caller reads are charged against the per-entry and archive caps. Bytes inflated only to finish an entry
// the caller skipped are charged against a separate budget instead: they are discarded, so memory is not at risk,
// but CPU is. The ratio check covers every inflated byte, retained or skipped, so a skipped bomb is still refused.
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
    private var retainedEntryBytes = 0L
    private var entryFrom = 0L
    private var totalBytes = 0L
    private var skippedBytes = 0L
    private var finishing = false

    override fun getNextEntry(): ZipEntry? {
        // super.getNextEntry finishes the previous entry through the read below. Whatever it inflates is the
        // remainder the caller did not read, so charge it to the skipped budget rather than to a retained entry.
        finishing = true
        val entry = try {
            super.getNextEntry()
        } catch (_: IllegalArgumentException) {
            throw fail("malformed archive")
        } finally {
            finishing = false
        } ?: return null
        if (++entries > limits.maxEntries) throw fail("too many entries")
        entryBytes = 0L
        retainedEntryBytes = 0L
        entryFrom = counting.count
        return entry
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) charge(n)
        return n
    }

    /** Skipping must inflate through [read] too, or a skipped bomb escapes every charge. */
    override fun skip(n: Long): Long {
        if (n <= 0) return 0L
        val buffer = ByteArray(minOf(n, SKIP_BUFFER_BYTES.toLong()).toInt())
        var skipped = 0L
        while (skipped < n) {
            val got = read(buffer, 0, minOf(buffer.size.toLong(), n - skipped).toInt())
            if (got < 0) break
            skipped += got
        }
        return skipped
    }

    private fun charge(n: Int) {
        entryBytes += n
        if (entryBytes >= limits.ratioFloorBytes) {
            val compressed = (counting.count - entryFrom).coerceAtLeast(1L)
            if (entryBytes / compressed > limits.maxRatio) throw fail("implausible compression")
        }
        if (finishing) {
            skippedBytes += n
            if (skippedBytes > limits.maxSkippedBytes) throw fail("too many skipped bytes")
        } else {
            retainedEntryBytes += n
            totalBytes += n
            if (retainedEntryBytes > limits.maxEntryBytes || totalBytes > limits.maxTotalBytes) throw fail("too large")
        }
    }

    private companion object {
        const val SKIP_BUFFER_BYTES = 8 * 1024
    }
}

internal class CountingInputStream(source: InputStream) : FilterInputStream(source) {
    var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

    override fun skip(n: Long): Long = super.skip(n).also { if (it > 0) count += it }
}
