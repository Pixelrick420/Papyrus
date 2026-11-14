package io.github.pixelrick420.papyrus.tts

// Keeps only how many units each chunk holds instead of building every unit up front, which for a large file is the file again.
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
