package com.papyrus.app.viewer

import java.io.IOException

/**
 * [length] consecutive characters of a piece that share one paragraph's properties. [para] is null
 * when the document carries no paragraph properties at all, which is how plain text is told apart
 * from text whose paragraphs were all in the default style.
 */
internal class DocRun(val length: Int, val para: DocParaProps?)

/**
 * Where a document keeps its formatting, and the way into it: the style sheet, and the paragraph
 * property runs (PAPX) found through a piece's `fc`. Everything here is optional enrichment, so a
 * structure that is missing or damaged is dropped and the text is read without it.
 */
internal class DocFormattingParser private constructor(
    private val styles: DocStyleSheet,
    private val papx: DocFkpIndex?,
) {
    /**
     * The runs of the [charCount] characters whose first sits at byte offset [fcFrom] of the
     * `WordDocument` stream, each character [bytesPerChar] bytes wide. Lazy: a piece can hold 16
     * million characters and the block budget usually ends the walk long before.
     */
    fun runs(fcFrom: Long, charCount: Int, bytesPerChar: Int): Sequence<DocRun> {
        if (papx == null) return sequenceOf(DocRun(charCount, null))
        return sequence {
            val fcTo = fcFrom + charCount.toLong() * bytesPerChar
            var emitted = 0
            for (run in papx.runs(fcFrom, fcTo)) {
                val upTo = ((run.to - fcFrom) / bytesPerChar).coerceAtMost(charCount.toLong()).toInt()
                if (upTo <= emitted) continue
                yield(DocRun(upTo - emitted, paragraphOf(run)))
                emitted = upTo
            }
            if (emitted < charCount) yield(DocRun(charCount - emitted, styles.ofStyle(0)))
        }
    }

    /** A PAPX is the paragraph's `istd` then its direct sprms; a paragraph with none is in Normal. */
    private fun paragraphOf(run: DocFkpIndex.Run): DocParaProps {
        val page = run.page ?: return styles.ofStyle(0)
        return styles.paragraph(u16(page, run.start), page, run.start + 2, run.end)
    }

    companion object {
        /** FibRgFcLcb97 index of fcPlcfBtePapx. */
        const val PAIR_BTE_PAPX = 13

        fun open(cfb: CompoundFile, fib: DocFib): DocFormattingParser {
            val streamSize = cfb.streamSize(DocFib.WORD_DOCUMENT) ?: 0L
            val pages = DocPageReader(cfb, streamSize)
            val papx = DocFkpIndex.open(cfb, fib, PAIR_BTE_PAPX, pages, paragraph = true)
            return DocFormattingParser(DocStyleSheet.parse(cfb, fib), papx)
        }
    }
}

/** [CompoundFile.read] for structures that only add formatting: a bad range is no data, not a failure. */
internal fun CompoundFile.readOrNull(name: String, offset: Long, length: Int): ByteArray? =
    try {
        read(name, offset, length)
    } catch (ignored: IOException) {
        null
    }

/**
 * 512-byte FKP pages of the `WordDocument` stream. Pages are read a few at a time, since a document
 * stores its formatting pages one after another and a [CompoundFile] read costs a pass over the
 * stream; a page that cannot be read is remembered as absent.
 */
internal class DocPageReader(private val cfb: CompoundFile, private val streamSize: Long) {
    private val cache = HashMap<Int, ByteArray?>()

    fun page(pn: Int): ByteArray? {
        if (cache.containsKey(pn)) return cache[pn]
        val first = pn.toLong() * PAGE
        if (pn < 0 || first + PAGE > streamSize) return remember(pn, null)
        val ahead = ((streamSize - first) / PAGE).coerceAtMost(READ_AHEAD.toLong()).toInt()
        val bytes = cfb.readOrNull(DocFib.WORD_DOCUMENT, first, ahead * PAGE)
            ?: cfb.readOrNull(DocFib.WORD_DOCUMENT, first, PAGE)
            ?: return remember(pn, null)
        if (cache.size > MAX_CACHED) cache.clear()
        for (k in 0 until bytes.size / PAGE) cache[pn + k] = bytes.copyOfRange(k * PAGE, (k + 1) * PAGE)
        return cache[pn]
    }

    private fun remember(pn: Int, page: ByteArray?): ByteArray? {
        cache[pn] = page
        return page
    }

    companion object {
        const val PAGE = 512
        private const val READ_AHEAD = 16
        private const val MAX_CACHED = 256
    }
}

/**
 * A PlcBtePapx or PlcBteChpx: `n + 1` ascending file positions, then `n` page numbers, so entry `i`
 * names the FKP page that holds the properties for characters at `[fc[i], fc[i + 1])`. A page is
 * `crun` run boundaries (4 bytes each, one more than there are runs), then per run an entry
 * pointing at its properties, with `crun` in the page's last byte. A paragraph entry is 13 bytes (a
 * word offset, then a 12-byte PHE) and a character entry is 1 byte (a word offset); offset 0 means
 * the run has no properties of its own.
 */
internal class DocFkpIndex private constructor(
    private val bteFc: LongArray,
    private val pageNumbers: IntArray,
    private val reader: DocPageReader,
    private val paragraph: Boolean,
) {
    /** `[from, to)` in file positions; its properties are `page[start, end)`, or none when [page] is null. */
    class Run(val from: Long, val to: Long, val page: ByteArray?, val start: Int, val end: Int)

    private val entryBytes = if (paragraph) PAPX_ENTRY_BYTES else CHPX_ENTRY_BYTES
    private val maxRuns = (DocPageReader.PAGE - 1 - 4) / (4 + entryBytes)

    /**
     * Runs covering file positions `[fcFrom, fcTo)` in order, with a property-less run wherever the
     * file says nothing, so the result always spans the whole range.
     */
    fun runs(fcFrom: Long, fcTo: Long): Sequence<Run> = sequence {
        var pos = fcFrom
        var entry = entryAt(fcFrom)
        while (pos < fcTo && entry < pageNumbers.size) {
            val entryEnd = bteFc[entry + 1]
            if (entryEnd <= pos) {
                entry++
                continue
            }
            val limit = minOf(entryEnd, fcTo)
            if (bteFc[entry] > pos) {
                val to = minOf(bteFc[entry], fcTo)
                yield(Run(pos, to, null, 0, 0))
                pos = to
                continue
            }
            val page = reader.page(pageNumbers[entry])
            val crun = if (page == null) 0 else page[DocPageReader.PAGE - 1].toInt() and 0xFF
            if (page == null || crun == 0 || crun > maxRuns) {
                yield(Run(pos, limit, null, 0, 0))
                pos = limit
                entry++
                continue
            }
            var i = 0
            while (i < crun && pos < limit) {
                val runFrom = u32(page, i * 4)
                val runTo = u32(page, (i + 1) * 4)
                i++
                if (runTo <= pos || runTo <= runFrom) continue
                if (runFrom > pos) {
                    val to = minOf(runFrom, limit)
                    yield(Run(pos, to, null, 0, 0))
                    pos = to
                    if (pos >= limit) break
                }
                val to = minOf(runTo, limit)
                val slice = propertiesOf(page, crun, i - 1)
                yield(if (slice == null) Run(pos, to, null, 0, 0) else Run(pos, to, page, slice.first, slice.last + 1))
                pos = to
            }
            if (pos < limit) {
                yield(Run(pos, limit, null, 0, 0))
                pos = limit
            }
            entry++
        }
        if (pos < fcTo) yield(Run(pos, fcTo, null, 0, 0))
    }

    /** Index of the last entry starting at or before [fc]; 0 if [fc] precedes them all. */
    private fun entryAt(fc: Long): Int {
        var lo = 0
        var hi = pageNumbers.size - 1
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (bteFc[mid] <= fc) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /** The bytes of run [index]'s properties in [page], or null if it has none or points outside the page. */
    private fun propertiesOf(page: ByteArray, crun: Int, index: Int): IntRange? {
        val entryOffset = (crun + 1) * 4 + index * entryBytes
        val word = page[entryOffset].toInt() and 0xFF
        if (word == 0) return null
        val at = word * 2
        if (at >= DocPageReader.PAGE - 1) return null
        val first: Int
        val size: Int
        if (!paragraph) {
            first = at + 1
            size = page[at].toInt() and 0xFF
        } else {
            // A PapxInFkp: `cb` words (the `istd` is counted, in 2 bytes, among its bytes), or 0 then a
            // second count when the first would not fit; either way the size is even apart from `cb`'s byte.
            val cb = page[at].toInt() and 0xFF
            if (cb != 0) {
                first = at + 1
                size = cb * 2 - 1
            } else {
                first = at + 2
                size = (page[at + 1].toInt() and 0xFF) * 2
            }
            if (size < 2) return null
        }
        val end = first + size
        return if (end <= DocPageReader.PAGE - 1) first until end else null
    }

    companion object {
        private const val PAPX_ENTRY_BYTES = 13
        private const val CHPX_ENTRY_BYTES = 1
        private const val MAX_BTE_BYTES = 4 * 1024 * 1024
        private const val PAGE_NUMBER_MASK = 0x3FFFFFL

        /** Reads the Plcf at FibRgFcLcb97 entry [pair], or null if it is absent, malformed, or not ascending. */
        fun open(cfb: CompoundFile, fib: DocFib, pair: Int, reader: DocPageReader, paragraph: Boolean): DocFkpIndex? {
            val lcb = fib.lcb(pair)
            // n + 1 four-byte positions and n four-byte page numbers: 8n + 4 bytes.
            if (lcb < 12 || lcb > MAX_BTE_BYTES || (lcb - 4) % 8 != 0L) return null
            val data = cfb.readOrNull(fib.tableStream, fib.fc(pair), lcb.toInt()) ?: return null
            val count = ((lcb - 4) / 8).toInt()
            val fcs = LongArray(count + 1) { u32(data, it * 4) }
            for (i in 1..count) if (fcs[i] < fcs[i - 1]) return null
            val pages = IntArray(count) { (u32(data, (count + 1) * 4 + it * 4) and PAGE_NUMBER_MASK).toInt() }
            return DocFkpIndex(fcs, pages, reader, paragraph)
        }
    }
}
