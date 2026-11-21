package io.github.pixelrick420.papyrus.data

data class ParsedSearch(
    val expression: String,
    val terms: List<String>,
)

// The split keeps letters, digits and marks, so a typed operator never reaches the parser and a vowel sign stays with its word;
// each word is quoted, and one long enough for a prefix match gets a trailing *.
object SearchQuery {

    /** Shortest word that earns a prefix match; the length the index's prefix tables start at. */
    const val MIN_PREFIX_LENGTH = 2

    /** Words kept from one query, so a pasted paragraph cannot become an expensive expression. */
    const val MAX_TERMS = 8

    private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}\\p{M}]+")

    fun parse(input: String): ParsedSearch? {
        val words = input.split(WORD_SEPARATOR).filter { it.isNotEmpty() }
        val longEnough = words.filter { it.length >= MIN_PREFIX_LENGTH }
        val usable = (longEnough.ifEmpty { words }).take(MAX_TERMS)
        if (usable.isEmpty()) return null
        val expression = usable.joinToString(" ") { word ->
            if (word.length >= MIN_PREFIX_LENGTH) "\"$word*\"" else "\"$word\""
        }
        return ParsedSearch(expression, usable)
    }
}
