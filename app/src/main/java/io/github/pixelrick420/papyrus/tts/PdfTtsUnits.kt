package io.github.pixelrick420.papyrus.tts

// Grows one page at a time so speech can start before the whole PDF is parsed.
class PdfTtsUnits(
    private val pageCount: Int,
    private val extract: suspend (Int) -> String,
) : AbstractList<TtsUnit>(), RandomAccess {

    private val lock = Any()
    private val published = ArrayList<TtsUnit>()
    private var nextPage = 0
    private var pagesWithUnits = 0

    override val size: Int get() = synchronized(lock) { published.size }

    override fun get(index: Int): TtsUnit = synchronized(lock) { published[index] }

    suspend fun publishNext(): Boolean {
        val page = synchronized(lock) {
            if (nextPage >= pageCount) return false
            nextPage++
        }
        val additions = TtsDocumentSource.paragraphs(extract(page)).map { TtsUnit(it.text, page, it.start) }
        synchronized(lock) {
            published.addAll(additions)
            if (additions.isNotEmpty()) pagesWithUnits++
        }
        return true
    }

    suspend fun publishThrough(page: Int) {
        if (page !in 0 until pageCount) return
        while (synchronized(lock) { nextPage <= page }) publishNext()
    }

    fun publishedPages(): Int = synchronized(lock) { pagesWithUnits }

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = System.identityHashCode(this)

    override fun toString(): String = "PdfTtsUnits($size units)"
}
