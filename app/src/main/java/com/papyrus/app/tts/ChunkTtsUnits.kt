package com.papyrus.app.tts

/**
 * The read-aloud units of plain text or code, one per paragraph of each chunk, made as they are asked
 * for. [TtsDocumentSource.unitsFromPlainText] builds all of them up front, which for a large file is
 * the file again; this keeps only how many units each chunk holds, and splits the one chunk a unit is read
 * from. A unit is the same either way, with the chunk as its anchor and its offset in that chunk as its start,
 * so the voice follows along and the viewer scrolls to the right chunk. Equal only to itself, because
 * comparing two lists would read every chunk.
 */
class ChunkTtsUnits private constructor(
    private val chunks: List<String>,
    /** `firstUnit[c]` is how many units the chunks before `c` hold; one more entry than chunks, the total. */
    private val firstUnit: IntArray,
) : AbstractList<TtsUnit>(), RandomAccess {

    private var heldChunk = -1
    private var heldParagraphs: List<TtsParagraph> = emptyList()

    override val size: Int get() = firstUnit[chunks.size]

    @Synchronized
    override fun get(index: Int): TtsUnit {
        if (index !in 0 until size) throw IndexOutOfBoundsException("unit $index of $size")
        val chunk = chunkOf(index)
        if (chunk != heldChunk) {
            heldParagraphs = TtsDocumentSource.paragraphs(chunks[chunk])
            heldChunk = chunk
        }
        val paragraph = heldParagraphs[index - firstUnit[chunk]]
        return TtsUnit(paragraph.text, chunk, paragraph.start)
    }

    /**
     * The first unit anchored at [chunk] or after it; the total when no chunk from there has one. Where
     * playback starts for a saved position, without reading the chunks before it.
     */
    fun firstUnitFrom(chunk: Int): Int = firstUnit[chunk.coerceIn(0, chunks.size)]

    /** The last chunk whose units begin at or before [index]; a chunk with no units shares its start with the next. */
    private fun chunkOf(index: Int): Int {
        var low = 0
        var high = chunks.size - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (firstUnit[middle] <= index) low = middle else high = middle - 1
        }
        return low
    }

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "ChunkTtsUnits($size units)"

    companion object {
        /** Counts the units of every chunk in one pass, reading each chunk once; [ensureActive] runs before each. */
        fun of(chunks: List<String>, ensureActive: () -> Unit = {}): ChunkTtsUnits {
            val firstUnit = IntArray(chunks.size + 1)
            var total = 0
            for (chunk in chunks.indices) {
                ensureActive()
                firstUnit[chunk] = total
                total += TtsDocumentSource.paragraphs(chunks[chunk]).size
            }
            firstUnit[chunks.size] = total
            return ChunkTtsUnits(chunks, firstUnit)
        }
    }
}
