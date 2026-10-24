package com.papyrus.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The ranking arithmetic over `matchinfo 'pcnalx'` blobs.
 *
 * The blobs below that are marked "recorded" are what SQLite actually returned for the FTS4 table
 * this app creates; the rest are hand-built layouts, which is the point — each test isolates one
 * input the formula is supposed to react to.
 */
class Bm25RankerTest {

    /** A `matchinfo` blob: little-endian 32-bit integers, as SQLite writes them. */
    private fun blob(vararg values: Int): ByteArray {
        val buffer = ByteBuffer.allocate(values.size * Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach(buffer::putInt)
        return buffer.array()
    }

    /**
     * One phrase, one column, [rows] documents in the table, [average] tokens per column on
     * average, [length] tokens in this document, [frequency] occurrences here and [documents]
     * documents containing it.
     */
    private fun onePhrase(
        rows: Int = 100,
        average: Int = 100,
        length: Int = 100,
        frequency: Int = 1,
        documents: Int = 1,
        occurrences: Int = frequency,
    ): ByteArray = blob(1, 1, rows, average, length, frequency, occurrences, documents)

    @Test
    fun `a document as long as the average scores exactly its idf`() {
        // With tf=1 and l=a the saturation and length terms cancel to 1, leaving the idf alone.
        val evenlySized = onePhrase(rows = 100, average = 100, length = 100, frequency = 1, documents = 1)

        val idf = Math.log(1.0 + 99.5 / 1.5)

        assertEquals(idf, Bm25Ranker.score(evenlySized), 1e-9)
    }

    @Test
    fun `recorded blobs rank the documents the way the formula says`() {
        // Recorded from the app's own FTS4 table: one phrase, four documents, six tokens on average.
        // medium: five tokens, one occurrence. longManyHits: fourteen tokens, four occurrences.
        // short: three tokens, one occurrence.
        val medium = blob(1, 1, 4, 6, 5, 1, 6, 3)
        val longManyHits = blob(1, 1, 4, 6, 14, 4, 6, 3)
        val short = blob(1, 1, 4, 6, 3, 1, 6, 3)

        val mediumScore = Bm25Ranker.score(medium)
        val longScore = Bm25Ranker.score(longManyHits)
        val shortScore = Bm25Ranker.score(short)

        assertTrue("more occurrences should win", longScore > mediumScore)
        assertTrue("a shorter document should win on equal occurrences", shortScore > mediumScore)
        assertEquals(0.3827731105683958, mediumScore, 1e-9)
        assertEquals(0.49042804791575706, longScore, 1e-9)
        assertEquals(0.4483913580944065, shortScore, 1e-9)
    }

    @Test
    fun `more occurrences of the same word score higher`() {
        val once = Bm25Ranker.score(onePhrase(frequency = 1))
        val thrice = Bm25Ranker.score(onePhrase(frequency = 3))

        assertTrue(thrice > once)
    }

    @Test
    fun `a word in a longer document scores lower, all else equal`() {
        val short = Bm25Ranker.score(onePhrase(length = 50))
        val long = Bm25Ranker.score(onePhrase(length = 5_000))

        assertTrue(short > long)
    }

    @Test
    fun `a rarer word scores higher`() {
        val rare = Bm25Ranker.score(onePhrase(documents = 1))
        val common = Bm25Ranker.score(onePhrase(documents = 90))

        assertTrue(rare > common)
    }

    @Test
    fun `a word in every document still scores above zero`() {
        // The classical idf goes negative here, which would rank the commonest word below the rarest.
        val everywhere = Bm25Ranker.score(onePhrase(documents = 100))

        assertTrue(everywhere > 0.0)
    }

    @Test
    fun `a document frequency above the row count cannot make the score negative`() {
        // A stale or malformed blob; clamping keeps the result a demotion rather than a disqualification.
        val impossible = Bm25Ranker.score(onePhrase(rows = 10, documents = 50))

        assertTrue(impossible > 0.0)
    }

    @Test
    fun `the phrases of a multi-word query add up`() {
        // Hand-built: p=2, c=1, n=100, a=100, l=100, then two cells of (tf, total, df).
        val one = onePhrase()
        val two = blob(2, 1, 100, 100, 100, 1, 100, 1, 1, 100, 1)

        assertEquals(2 * Bm25Ranker.score(one), Bm25Ranker.score(two), 1e-9)
    }

    @Test
    fun `a phrase absent from the document contributes nothing`() {
        assertEquals(0.0, Bm25Ranker.score(onePhrase(frequency = 0)), 0.0)
    }

    @Test
    fun `a truncated or unreadable blob scores zero instead of throwing`() {
        assertEquals(0.0, Bm25Ranker.score(ByteArray(0)), 0.0)
        assertEquals(0.0, Bm25Ranker.score(ByteArray(5)), 0.0)
        // Whole ints, but fewer than the three counts: 8 bytes is two values.
        assertEquals(0.0, Bm25Ranker.score(blob(1, 1)), 0.0)
        // The counts promise more blocks than the blob holds: read as zeros, never an exception.
        assertEquals(0.0, Bm25Ranker.score(blob(1, 1, 100)), 0.0)
    }

    @Test
    fun `impossible counts score zero`() {
        assertEquals(0.0, Bm25Ranker.score(blob(0, 1, 100)), 0.0)
        assertEquals(0.0, Bm25Ranker.score(blob(1, 0, 100)), 0.0)
        assertEquals(0.0, Bm25Ranker.score(blob(1, 1, 0)), 0.0)
    }

    @Test
    fun `an empty index scores zero`() {
        // A document with no tokens at all: a[c] of 0 must not divide by zero.
        assertEquals(0.0, Bm25Ranker.score(blob(1, 1, 100, 0, 0, 0, 1, 0)), 0.0)
    }
}
