package com.papyrus.app.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.min

/**
 * BM25 over the `matchinfo` blob FTS4 returns for `'pcnalx'`, the only ranking signal the index
 * offers without reading a single document's text.
 *
 * The blob is a run of 32-bit integers in machine byte order — little-endian on every ABI Android
 * ships — laid out as:
 *
 *  - `p`, `c`, `n`: phrases in the query, columns in the table, rows in the table.
 *  - `a[c]`: average tokens per column, where the length normalisation starts.
 *  - `l[c]`: tokens in this row per column, its "document length".
 *  - `x[3*c*p]`: for each phrase and column, three values — occurrences in this row (the term
 *    frequency), occurrences in all rows, and rows containing at least one occurrence (the
 *    document frequency) — ordered by phrase, then column, as
 *    `3 * (column + phrase * columns) + {0, 1, 2}` from the start of the block.
 *
 * The middle value is never read: BM25 needs no collection-wide hit count. The table here has a
 * single column, so each cell's document frequency is the phrase's document frequency outright; a
 * second column would make that a union across columns and this would have to be revisited rather
 * than reused unchanged.
 *
 * A truncated or unreadable blob scores 0 instead of throwing: a broken ranking should demote a
 * result, not fail a search.
 *
 * Counts are read as signed integers, which a library would have to exceed two billion tokens in
 * one column to notice.
 */
object Bm25Ranker {

    /** Term-frequency saturation: how quickly further occurrences stop improving a score. */
    private const val K1 = 1.2

    /** Length normalisation: how far a long document is penalised against a short one. */
    private const val B = 0.75

    /** `p`, `c` and `n`, before the `a`, `l` and `x` blocks. */
    private const val LEADING_COUNT = 3

    /** Higher is better. Only comparable between hits of the same search. */
    fun score(matchInfo: ByteArray): Double {
        val ints = readLittleEndianInts(matchInfo) ?: return 0.0
        if (ints.size < LEADING_COUNT) return 0.0
        val phrases = ints[0]
        val columns = ints[1]
        val rows = ints[2]
        if (phrases <= 0 || columns <= 0 || rows <= 0) return 0.0

        var total = 0.0
        for (phrase in 0 until phrases) {
            for (column in 0 until columns) {
                val cell = LEADING_COUNT + 2 * columns + 3 * (column + phrase * columns)
                val termFrequency = ints.getOrElse(cell) { 0 }
                val documentFrequency = min(ints.getOrElse(cell + 2) { 0 }, rows)
                if (termFrequency <= 0 || documentFrequency <= 0) continue
                val length = ints.getOrElse(LEADING_COUNT + columns + column) { 0 }.toDouble()
                val average = ints.getOrElse(LEADING_COUNT + column) { 0 }.coerceAtLeast(1).toDouble()
                // The +1 inside the logarithm keeps the idf positive for a phrase that appears in
                // most documents, where the classical formula would go negative and rank the
                // commonest word below the rarest.
                val idf = ln(1.0 + (rows - documentFrequency + 0.5) / (documentFrequency + 0.5))
                val normalisation = K1 * (1 - B + B * length / average)
                total += idf * (termFrequency * (K1 + 1)) / (termFrequency + normalisation)
            }
        }
        return total
    }

    private fun readLittleEndianInts(bytes: ByteArray): IntArray? {
        if (bytes.size < LEADING_COUNT * Int.SIZE_BYTES || bytes.size % Int.SIZE_BYTES != 0) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(bytes.size / Int.SIZE_BYTES) { buffer.getInt() }
    }
}
