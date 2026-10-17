package com.papyrus.app.viewer

/** What a stretch of source is, for colouring; the UI maps each kind to a style. */
enum class TokenKind { COMMENT, STRING, NUMBER, KEYWORD, LITERAL, TYPE, FUNCTION, ANNOTATION, VARIABLE, PROPERTY, TAG, ATTRIBUTE }

/** Append-only token list in three int arrays, not an object per token: a chunk holds hundreds. */
class SyntaxTokens {
    var size = 0
        private set
    private var starts = IntArray(INITIAL_CAPACITY)
    private var ends = IntArray(INITIAL_CAPACITY)
    private var kinds = IntArray(INITIAL_CAPACITY)

    internal fun add(start: Int, end: Int, kind: TokenKind) {
        if (size == starts.size) {
            starts = starts.copyOf(size * 2)
            ends = ends.copyOf(size * 2)
            kinds = kinds.copyOf(size * 2)
        }
        starts[size] = start
        ends[size] = end
        kinds[size] = kind.ordinal
        size++
    }

    fun start(index: Int): Int = starts[index]

    fun end(index: Int): Int = ends[index]

    fun kind(index: Int): TokenKind = TokenKind.entries[kinds[index]]

    private companion object {
        const val INITIAL_CAPACITY = 32
    }
}

/**
 * The scanner's state at the start of each chunk: 0, or the construct left open by the chunk before.
 * A chunk is 40 lines, so without it a comment crossing a boundary would colour as code from there on.
 */
class LexStates internal constructor(private val states: IntArray) {
    operator fun get(chunk: Int): Int = states.getOrElse(chunk) { 0 }
}

/** A comment or string that can run over several lines, so the state carries into the next chunk. */
class Delimited(val open: String, val close: String, val kind: TokenKind)

enum class Flavor { CODE, CSS, MARKUP }

/** Keys at the start of a line: `key: value` (YAML) or `key = value` and `[section]` (INI, TOML). */
enum class LineKeys { NONE, YAML, EQUALS }

/**
 * A language as data: one scanner serves them all, switched by these fields. Not a parser: it colours
 * what a reader scans for (comments, strings, numbers, keywords) and leaves the rest alone.
 */
class CodeLanguage(
    val id: String,
    keywords: String = "",
    literals: String = "",
    val caseInsensitive: Boolean = false,
    val lineComments: List<String> = emptyList(),
    val multiline: List<Delimited> = emptyList(),
    /** Opens a string that ends at the same character or at the end of the line. */
    val quotes: String = "\"'",
    /** Quotes from [quotes] that take no backslash escape. */
    val rawQuotes: String = "",
    /** Quotes that only open a character literal, since the mark may also be a lifetime or symbol. */
    val charLiteralQuotes: String = "",
    val escape: Char = '\\',
    /** `@name`: a decorator, an annotation, a CSS at-rule. */
    val annotations: Boolean = false,
    /** `#include`, `#region`: a directive at the start of a line. */
    val preprocessor: Boolean = false,
    /** `$name` and `${name}` are variables, so `$` does not start an identifier. */
    val dollarVariables: Boolean = false,
    /** `$(NAME)` too, up to the closing parenthesis (make). */
    val parenVariables: Boolean = false,
    /** `CamelCase` is a type and `ALL_CAPS` a constant. */
    val capitalizedTypes: Boolean = true,
    /** A name followed by `(` is a call. */
    val functionCalls: Boolean = true,
    /** A string followed by `:` is a key (JSON, YAML). */
    val stringKeys: Boolean = false,
    val lineKeys: LineKeys = LineKeys.NONE,
    val flavor: Flavor = Flavor.CODE,
) {
    internal val keywordSet: Set<String> = wordSet(keywords, caseInsensitive)
    internal val literalSet: Set<String> = wordSet(literals, caseInsensitive)
    internal val multilineStarts: String = multiline.map { it.open[0] }.distinct().joinToString("")

    /** The state meaning "inside a tag": one past the multi-line constructs, which take 1..size. */
    internal val tagState: Int get() = multiline.size + 1

    private companion object {
        fun wordSet(words: String, lower: Boolean): Set<String> =
            words.split(' ', '\n').filter { it.isNotEmpty() }.map { if (lower) it.lowercase() else it }.toHashSet()
    }
}

object SyntaxHighlighter {

    /**
     * A chunk longer than this is shown plain: styling tens of thousands of spans costs more than it
     * helps, and such a chunk is minified code, which colour does not make readable.
     */
    const val MAX_HIGHLIGHT_CHARS = 20_000

    /** Tokens of one [text] that starts in [startState] (0, or the `LexStates` entry for its chunk). */
    fun tokenize(language: CodeLanguage, text: String, startState: Int = 0): SyntaxTokens {
        val tokens = SyntaxTokens()
        Scanner(language, text, tokens).run(startState)
        return tokens
    }

    /** The state [text] leaves behind, found without building any tokens. */
    fun endState(language: CodeLanguage, text: String, startState: Int = 0): Int =
        Scanner(language, text, null).run(startState)

    /** One pass over every chunk, so each can later be coloured alone, in any order. */
    fun chunkStartStates(language: CodeLanguage, chunks: List<String>): LexStates {
        val states = IntArray(chunks.size)
        var state = 0
        for (i in chunks.indices) {
            states[i] = state
            state = endState(language, chunks[i], state)
        }
        return LexStates(states)
    }
}

/**
 * With [out] null this only tracks where strings and comments begin and end, which is all the state
 * needs, so the pass over a large file skips naming every identifier.
 */
private class Scanner(
    private val lang: CodeLanguage,
    private val text: String,
    private val out: SyntaxTokens?,
) {
    private val n = text.length
    private var pos = 0
    private var lineStart = true

    /** Returns the state at the end: 0 unless a multi-line construct is still open. */
    fun run(startState: Int): Int {
        var state = startState
        if (state != 0) {
            state = if (lang.flavor == Flavor.MARKUP && state == lang.tagState) tagBody() else closeMultiline(state)
            if (state != 0) return state
        }
        return if (lang.flavor == Flavor.MARKUP) runMarkup() else runCode()
    }

    private fun emit(start: Int, end: Int, kind: TokenKind) {
        if (end > start) out?.add(start, end, kind)
    }

    private fun peek(offset: Int = 1): Char = if (pos + offset < n) text[pos + offset] else NUL

    // --- Code ---------------------------------------------------------------------------------

    private fun runCode(): Int {
        while (pos < n) {
            val c = text[pos]
            if (c == '\n') {
                lineStart = true
                pos++
                continue
            }
            if (c == ' ' || c == '\t' || c == '\r') {
                pos++
                continue
            }
            val first = lineStart
            lineStart = false
            if (first && lang.lineKeys != LineKeys.NONE && lineKey()) continue
            val open = token(c, first)
            if (open != 0) return open
        }
        return 0
    }

    /** Consumes one token at [pos]; returns a state only when a multi-line construct ran off the end. */
    private fun token(c: Char, atLineStart: Boolean): Int {
        if (lang.multilineStarts.indexOf(c) >= 0) {
            val state = openMultiline()
            if (state >= 0) return state
        }
        if (lineComment()) return 0
        when {
            lang.charLiteralQuotes.indexOf(c) >= 0 -> charLiteral()
            lang.quotes.indexOf(c) >= 0 -> quoted()
            // `.5` is a number, but the second dot of `1..5` is a range operator, not the start of one.
            c.isDigit() || (c == '.' && peek().isDigit() && (pos == 0 || text[pos - 1] != '.')) -> number()
            isIdentStart(c) -> identifier()
            c == '@' && lang.annotations -> annotation()
            c == '#' && lang.flavor == Flavor.CSS -> cssHash()
            c == '#' && lang.preprocessor && atLineStart -> directive()
            c == '$' && lang.dollarVariables -> if (!variable()) pos++
            c == '.' && lang.flavor == Flavor.CSS && isIdentStart(peek()) -> cssClass()
            c == '!' && lang.flavor == Flavor.CSS && text.startsWith("!important", pos) -> {
                emit(pos, pos + IMPORTANT_LENGTH, TokenKind.KEYWORD)
                pos += IMPORTANT_LENGTH
            }
            else -> pos++
        }
        return 0
    }

    /** -1 when nothing opens here; otherwise the state left behind, 0 if it closed inside the chunk. */
    private fun openMultiline(): Int {
        for (i in lang.multiline.indices) {
            val d = lang.multiline[i]
            if (!text.startsWith(d.open, pos)) continue
            val close = text.indexOf(d.close, pos + d.open.length)
            if (close < 0) {
                emit(pos, n, d.kind)
                pos = n
                return i + 1
            }
            emit(pos, close + d.close.length, d.kind)
            pos = close + d.close.length
            return 0
        }
        return -1
    }

    /** Resumes a construct the previous chunk left open, from the start of this one. */
    private fun closeMultiline(state: Int): Int {
        val d = lang.multiline.getOrNull(state - 1) ?: return 0
        val close = text.indexOf(d.close, pos)
        if (close < 0) {
            emit(pos, n, d.kind)
            pos = n
            return state
        }
        emit(pos, close + d.close.length, d.kind)
        pos = close + d.close.length
        return 0
    }

    private fun lineComment(): Boolean {
        for (marker in lang.lineComments) {
            if (!text.startsWith(marker, pos)) continue
            val end = lineEnd(pos)
            emit(pos, end, TokenKind.COMMENT)
            pos = end
            return true
        }
        return false
    }

    private fun lineEnd(from: Int): Int {
        val e = text.indexOf('\n', from)
        return if (e < 0) n else e
    }

    private fun quoted() {
        val start = pos
        val quote = text[pos]
        val escapes = lang.rawQuotes.indexOf(quote) < 0 && lang.escape != NUL
        var i = pos + 1
        var closed = false
        while (i < n) {
            val ch = text[i]
            if (ch == '\n') break
            if (escapes && ch == lang.escape) {
                i += 2
                continue
            }
            if (ch == quote) {
                // Where there is no backslash escape a doubled quote is the escape: SQL's 'it''s', YAML's 'it''s'.
                if (!escapes && i + 1 < n && text[i + 1] == quote) {
                    i += 2
                    continue
                }
                i++
                closed = true
                break
            }
            i++
        }
        val end = minOf(i, n)
        pos = end
        if (out == null) return
        val isKey = closed && lang.stringKeys && followedByColon(end)
        emit(start, end, if (isKey) TokenKind.PROPERTY else TokenKind.STRING)
    }

    private fun followedByColon(from: Int): Boolean {
        var k = from
        while (k < n && (text[k] == ' ' || text[k] == '\t')) k++
        return k < n && text[k] == ':'
    }

    /** `'a'`, `'\n'`, `'\u{1F600}'`; a lone `'` (a Rust lifetime) is left alone. */
    private fun charLiteral() {
        val quote = text[pos]
        var end = -1
        val next = peek()
        if (next == '\\') {
            var i = pos + 2
            val limit = minOf(n, pos + ESCAPED_CHAR_REACH)
            while (i < limit && text[i] != '\n') {
                if (text[i] == quote) {
                    end = i + 1
                    break
                }
                i++
            }
        } else if (next != '\n' && next != NUL) {
            if (peek(2) == quote) {
                end = pos + 3
            } else if (Character.isHighSurrogate(next) && peek(3) == quote) {
                end = pos + 4
            }
        }
        if (end < 0) {
            pos++
            return
        }
        emit(pos, end, TokenKind.STRING)
        pos = end
    }

    private fun number() {
        val start = pos
        val hex = text[pos] == '0' && (peek() == 'x' || peek() == 'X')
        pos++
        while (pos < n) {
            val ch = text[pos]
            when {
                // Digits, hex digits, `_` separators and type suffixes (10L, 1.5f, 0xFFu) alike.
                ch.isLetterOrDigit() || ch == '_' -> pos++
                // `1..5` is a range and `x.method` a call; only a digit after the dot continues the number.
                ch == '.' && peek().isDigit() -> pos++
                ch == '%' && lang.flavor == Flavor.CSS -> {
                    pos++
                    break
                }
                (ch == '+' || ch == '-') && !hex && (text[pos - 1] == 'e' || text[pos - 1] == 'E') && peek().isDigit() -> pos++
                else -> break
            }
        }
        emit(start, pos, TokenKind.NUMBER)
    }

    private fun isIdentStart(c: Char): Boolean = when {
        c.isLetter() || c == '_' -> true
        c == '$' -> !lang.dollarVariables
        c == '-' -> lang.flavor == Flavor.CSS && (peek().isLetter() || peek() == '-')
        else -> false
    }

    private fun isIdentPart(c: Char): Boolean = when {
        c.isLetterOrDigit() || c == '_' -> true
        c == '$' -> !lang.dollarVariables
        c == '-' -> lang.flavor == Flavor.CSS
        else -> false
    }

    private fun identifier() {
        val start = pos
        pos++
        while (pos < n && isIdentPart(text[pos])) pos++
        if (out == null) return
        classify(start, pos)?.let { emit(start, pos, it) }
    }

    private fun classify(start: Int, end: Int): TokenKind? {
        val word = text.substring(start, end)
        val key = if (lang.caseInsensitive) word.lowercase() else word
        return when {
            key in lang.keywordSet -> TokenKind.KEYWORD
            key in lang.literalSet -> TokenKind.LITERAL
            lang.flavor == Flavor.CSS && isCssProperty(end) -> TokenKind.PROPERTY
            lang.capitalizedTypes && word[0].isUpperCase() && word.any { it.isLowerCase() } -> TokenKind.TYPE
            lang.functionCalls && end < n && text[end] == '(' -> TokenKind.FUNCTION
            lang.capitalizedTypes && isConstantName(word) -> TokenKind.LITERAL
            else -> null
        }
    }

    private fun isConstantName(word: String): Boolean =
        word.length >= 2 && word.any { it.isLetter() } && word.all { it.isUpperCase() || it.isDigit() || it == '_' }

    /** `color: red` yes; `a:hover` no, because a pseudo-class has no space after its colon. */
    private fun isCssProperty(end: Int): Boolean {
        var k = end
        while (k < n && (text[k] == ' ' || text[k] == '\t')) k++
        if (k >= n || text[k] != ':' || (k + 1 < n && text[k + 1] == ':')) return false
        if (k + 1 >= n) return true
        val after = text[k + 1]
        return after == ' ' || after == '\t' || after == '\n' || after == '\r' || after.isDigit() ||
            after == '#' || after == '"' || after == '\'' || after == '-' || after == '('
    }

    private fun annotation() {
        val start = pos
        pos++
        if (pos >= n || !isIdentStart(text[pos])) return
        while (pos < n && (isIdentPart(text[pos]) || (text[pos] == '.' && pos + 1 < n && isIdentStart(text[pos + 1])))) pos++
        emit(start, pos, TokenKind.ANNOTATION)
    }

    private fun directive() {
        val start = pos
        pos++
        while (pos < n && (text[pos] == ' ' || text[pos] == '\t')) pos++
        val word = pos
        while (pos < n && text[pos].isLetter()) pos++
        if (pos > word) emit(start, pos, TokenKind.ANNOTATION)
    }

    private fun cssHash() {
        val start = pos
        pos++
        val body = pos
        while (pos < n && (text[pos].isLetterOrDigit() || text[pos] == '_' || text[pos] == '-')) pos++
        if (pos == body) return
        val length = pos - body
        val isColour = (length == 3 || length == 4 || length == 6 || length == 8) &&
            (body until pos).all { text[it].isDigit() || text[it].lowercaseChar() in 'a'..'f' }
        emit(start, pos, if (isColour) TokenKind.NUMBER else TokenKind.TYPE)
    }

    private fun cssClass() {
        val start = pos
        pos++
        while (pos < n && isIdentPart(text[pos])) pos++
        emit(start, pos, TokenKind.TYPE)
    }

    /**
     * `$name`, `${name}`, `$1`, `$?`, and with [CodeLanguage.parenVariables] `$(NAME)`; false for a bare `$`.
     */
    private fun variable(): Boolean {
        var i = pos + 1
        if (i >= n) return false
        val c = text[i]
        when {
            c == '{' -> {
                val close = text.indexOf('}', i)
                if (close < 0 || close > lineEnd(i)) return false
                i = close + 1
            }
            c == '(' && lang.parenVariables -> {
                var depth = 0
                val end = lineEnd(i)
                while (i < end) {
                    if (text[i] == '(') depth++
                    if (text[i] == ')' && --depth == 0) break
                    i++
                }
                if (i >= end) return false
                i++
            }
            c.isLetter() || c == '_' -> {
                i++
                while (i < n && (text[i].isLetterOrDigit() || text[i] == '_')) i++
            }
            c.isDigit() -> {
                i++
                while (i < n && text[i].isDigit()) i++
            }
            c in SPECIAL_VARIABLES -> i++
            else -> return false
        }
        emit(pos, i, TokenKind.VARIABLE)
        pos = i
        return true
    }

    // --- Keys at the start of a line (YAML, INI, TOML) -----------------------------------------------

    private fun lineKey(): Boolean = when (lang.lineKeys) {
        LineKeys.YAML -> yamlKey()
        LineKeys.EQUALS -> equalsKey()
        LineKeys.NONE -> false
    }

    private fun yamlKey(): Boolean {
        var i = pos
        // List markers: `- name: x` is still the key `name`.
        while (i + 1 < n && text[i] == '-' && text[i + 1] == ' ') {
            i += 2
            while (i < n && text[i] == ' ') i++
        }
        val keyStart = i
        var j = i
        while (j < n) {
            val ch = text[j]
            // A quote or comment first means the line is not a plain key; quoted keys are the string rule's.
            if (ch == '\n' || ch == '#' || ch == '"' || ch == '\'') return false
            if (ch == ':' && (j + 1 >= n || text[j + 1] == ' ' || text[j + 1] == '\n' || text[j + 1] == '\r')) break
            j++
        }
        if (j >= n || j == keyStart) return false
        var end = j
        while (end > keyStart && text[end - 1] == ' ') end--
        emit(keyStart, end, TokenKind.PROPERTY)
        pos = j + 1
        return true
    }

    private fun equalsKey(): Boolean {
        if (text[pos] == '[') {
            val close = text.indexOf(']', pos)
            if (close < 0 || close > lineEnd(pos)) return false
            var end = close + 1
            while (end < n && text[end] == ']') end++
            emit(pos, end, TokenKind.TAG)
            pos = end
            return true
        }
        var j = pos
        while (j < n && (text[j].isLetterOrDigit() || text[j] in KEY_PUNCTUATION)) j++
        if (j == pos) return false
        var k = j
        while (k < n && (text[k] == ' ' || text[k] == '\t')) k++
        if (k >= n || (text[k] != '=' && text[k] != ':')) return false
        emit(pos, j, TokenKind.PROPERTY)
        pos = k + 1
        return true
    }

    // --- Markup (HTML, XML, SVG) ---------------------------------------------------------------------

    private fun runMarkup(): Int {
        while (pos < n) {
            val c = text[pos]
            if (c == '&') {
                entity()
                continue
            }
            if (c != '<') {
                pos++
                continue
            }
            val comment = openMultiline()
            if (comment > 0) return comment
            if (comment == 0) continue
            val start = pos
            var i = pos + 1
            if (i < n && text[i] == '/') i++ else if (i < n && (text[i] == '!' || text[i] == '?')) i++
            val name = i
            while (i < n && isTagNameChar(text[i])) i++
            if (i == name) {
                // A bare `<` in text, such as `a < b`.
                pos++
                continue
            }
            emit(start, i, TokenKind.TAG)
            pos = i
            val state = tagBody()
            if (state != 0) return state
        }
        return 0
    }

    /** From after a tag's name to its closing `>`; returns the in-tag state if the text ends first. */
    private fun tagBody(): Int {
        while (pos < n) {
            val c = text[pos]
            when {
                c == '>' -> {
                    emit(pos, pos + 1, TokenKind.TAG)
                    pos++
                    return 0
                }
                (c == '/' || c == '?') && peek() == '>' -> {
                    emit(pos, pos + 2, TokenKind.TAG)
                    pos += 2
                    return 0
                }
                c == '"' || c == '\'' -> {
                    val close = text.indexOf(c, pos + 1)
                    val end = if (close < 0) n else close + 1
                    emit(pos, end, TokenKind.STRING)
                    pos = end
                }
                c.isLetter() || c == '_' || c == ':' -> {
                    val start = pos
                    pos++
                    while (pos < n && isTagNameChar(text[pos])) pos++
                    emit(start, pos, TokenKind.ATTRIBUTE)
                }
                else -> pos++
            }
        }
        return lang.tagState
    }

    private fun isTagNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == ':' || c == '-' || c == '.'

    /** `&amp;`, `&#169;`: a short run of name characters ended by `;`. */
    private fun entity() {
        var i = pos + 1
        val limit = minOf(n, pos + ENTITY_REACH)
        while (i < limit && (text[i].isLetterOrDigit() || text[i] == '#')) i++
        if (i > pos + 1 && i < n && text[i] == ';') {
            emit(pos, i + 1, TokenKind.LITERAL)
            pos = i + 1
        } else {
            pos++
        }
    }
}

private const val NUL = '\u0000'
private const val IMPORTANT_LENGTH = 10
private const val ESCAPED_CHAR_REACH = 12
private const val ENTITY_REACH = 12
private const val SPECIAL_VARIABLES = "@*#?!\$-&"
private const val KEY_PUNCTUATION = "_.-/\$@"
