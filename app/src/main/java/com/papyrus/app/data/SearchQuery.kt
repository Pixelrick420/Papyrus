package com.papyrus.app.data

/** What [SearchQuery.parse] made of what the reader typed. */
data class ParsedSearch(
    /** An FTS4 MATCH expression, e.g. `"scal*" "paper*"`. */
    val expression: String,
    /** The words it was built from, in the order they were typed. */
    val terms: List<String>,
)

/**
 * Turns the reader's text into an FTS4 MATCH expression.
 *
 * Every word is quoted, which is what makes this safe rather than clever: inside a quoted phrase
 * FTS4 treats `AND`, `OR`, `NOT`, `NEAR`, `-`, `^`, `:` and `*` as literal text, so nothing the
 * reader can type reaches the query parser as an operator and a stray `(` cannot turn a search
 * into a syntax error. The split below drops everything that is not a letter or a digit before
 * quoting, so a token can never even contain the one character a quoted phrase does not tolerate.
 *
 * Every word of two characters or more also gets a trailing `*` — a prefix match — written *inside*
 * the quotes, `"scal*"`, which is the only form FTS4 reads as one: after the closing quote the same
 * asterisk is ignored, so `"scal"*` would quietly mean an exact match. The index keeps prefix tables
 * for lengths 2 and 3 (`prefix = [2, 3]` on [DocumentTextFtsEntity]), so `scal` finds "scale",
 * "scaling" and "Scality" from the index instead of by scanning every token. Longer prefixes are
 * still valid queries, they are simply not covered by those tables.
 *
 * Words are joined by a space — FTS4's implicit AND — so a document must contain all of them.
 * Single-character words are dropped as soon as a longer one is present: they match nearly every
 * document, so they cost a scan and change no ranking. A query made only of them is still
 * honoured, without the `*`, because typing "a" is a request rather than a mistake.
 */
object SearchQuery {

    /** Shortest word that earns a prefix match; the length the index's prefix tables start at. */
    const val MIN_PREFIX_LENGTH = 2

    /** Words kept from one query, so a pasted paragraph cannot become an expensive expression. */
    const val MAX_TERMS = 8

    /** Anything that is not a letter or a digit separates words, for this code and the tokenizer. */
    private val WORD_SEPARATOR = Regex("[^\\p{L}\\p{N}]+")

    /** Null when there is nothing to search for, which is the caller's cue to show nothing. */
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
