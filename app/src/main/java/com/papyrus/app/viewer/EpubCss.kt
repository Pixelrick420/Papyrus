package com.papyrus.app.viewer

/** Rules kept per stylesheet source (the book's, a chapter's); the rest are ignored. */
internal const val MAX_CSS_RULES = 500

/** Parts one selector may name (an `id` and its classes); a longer one is not a selector this subset reads. */
internal const val MAX_SELECTOR_COMPOUNDS = 4

private const val SPECIFICITY_ID = 100
private const val SPECIFICITY_CLASS = 10
private const val SPECIFICITY_TYPE = 1
private const val WEIGHT_BOLD = 600
private const val WEIGHT_NORMAL_MAX = 500

/**
 * What a style says about the three things the viewer can draw: bold, italic and alignment. Each is a
 * tri-state, null meaning "not said", so a stylesheet can also take a markup bold away, as a browser would.
 */
internal class CssDecl(val bold: Boolean? = null, val italic: Boolean? = null, val align: BlockAlign? = null) {
    val isEmpty: Boolean get() = bold == null && italic == null && align == null

    /** This layer wins wherever it has a say; [base] decides the rest. */
    fun mergeOver(base: CssDecl): CssDecl = CssDecl(bold ?: base.bold, italic ?: base.italic, align ?: base.align)

    companion object {
        val EMPTY = CssDecl()
    }
}

/** [format] with what [css] says about bold and italic laid over it. */
internal fun CharFormat.withCss(css: CssDecl): CharFormat =
    if (css.bold == null && css.italic == null) this else copy(bold = css.bold ?: bold, italic = css.italic ?: italic)

/** A simple compound selector: an optional type (`*` or absent matches any), at most one id, any classes. */
internal class CssSelector(val type: String?, val id: String?, val classes: List<String>) {
    val specificity: Int
        get() = (if (id != null) SPECIFICITY_ID else 0) + classes.size * SPECIFICITY_CLASS +
            (if (type != null) SPECIFICITY_TYPE else 0)

    fun matches(tag: String, elementId: String?, elementClasses: Set<String>): Boolean =
        (type == null || type == tag) && (id == null || id == elementId) && elementClasses.containsAll(classes)
}

internal class CssRule(val selector: CssSelector, val decl: CssDecl, val order: Int)

/**
 * Rules in cascade order. The id (100), class (10) and type (1) of a selector give its specificity; a later
 * rule beats an earlier one of the same specificity; each property is decided on its own.
 */
internal class CssSheet(rules: List<CssRule>) {
    private val byId = HashMap<String, MutableList<CssRule>>()
    private val byClass = HashMap<String, MutableList<CssRule>>()
    private val byType = HashMap<String, MutableList<CssRule>>()
    private val any = ArrayList<CssRule>()

    init {
        for (rule in rules) {
            val s = rule.selector
            when {
                s.id != null -> byId.getOrPut(s.id) { ArrayList() } += rule
                s.classes.isNotEmpty() -> byClass.getOrPut(s.classes.first()) { ArrayList() } += rule
                s.type != null -> byType.getOrPut(s.type) { ArrayList() } += rule
                else -> any += rule
            }
        }
    }

    /** What the rules say about an element, or null when none applies. */
    fun declFor(tag: String, id: String?, classes: Set<String>): CssDecl? {
        val found = ArrayList<CssRule>()
        id?.let { byId[it] }?.let { found += it }
        for (name in classes) byClass[name]?.let { found += it }
        byType[tag]?.let { found += it }
        found += any
        val matching = found.filter { it.selector.matches(tag, id, classes) }
        if (matching.isEmpty()) return null
        var result = CssDecl.EMPTY
        for (rule in matching.sortedWith(compareBy({ it.selector.specificity }, { it.order }))) {
            result = rule.decl.mergeOver(result)
        }
        return result
    }
}

/** Rules from [text], numbered from [firstOrder]; anything this subset cannot read is skipped, nothing throws. */
internal fun parseCss(text: String, firstOrder: Int = 0): List<CssRule> {
    val source = stripComments(text)
    val rules = ArrayList<CssRule>()
    var i = 0
    while (i < source.length && rules.size < MAX_CSS_RULES) {
        while (i < source.length && source[i].isWhitespace()) i++
        if (i >= source.length) break
        if (source[i] == '@') {
            i = skipAtRule(source, i)
            continue
        }
        val open = source.indexOf('{', i)
        if (open < 0) break
        val close = source.indexOf('}', open)
        // A rule that never ends is not a rule.
        if (close < 0) break
        val decl = parseDeclarations(source.substring(open + 1, close))
        if (!decl.isEmpty) {
            for (part in source.substring(i, open).split(',')) {
                if (rules.size >= MAX_CSS_RULES) break
                parseSelector(part)?.let { rules += CssRule(it, decl, firstOrder + rules.size) }
            }
        }
        i = close + 1
    }
    return rules
}

private fun stripComments(text: String): String {
    if (!text.contains("/*")) return text
    val out = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val start = text.indexOf("/*", i)
        if (start < 0) {
            out.append(text, i, text.length)
            break
        }
        out.append(text, i, start)
        val end = text.indexOf("*/", start + 2)
        // A comment that never ends takes the rest of the sheet with it, as in a browser.
        if (end < 0) break
        i = end + 2
    }
    return out.toString()
}

/** The index after the at-rule at [from]: a statement up to `;` (`@import`), or a block up to its balanced `}`. */
private fun skipAtRule(source: String, from: Int): Int {
    var i = from
    while (i < source.length && source[i] != ';' && source[i] != '{') i++
    if (i >= source.length) return source.length
    if (source[i] == ';') return i + 1
    var depth = 0
    while (i < source.length) {
        when (source[i]) {
            '{' -> depth++
            '}' -> if (--depth == 0) return i + 1
        }
        i++
    }
    return source.length
}

/** A selector of one compound; null for a combinator, a pseudo-class, an attribute test or anything unrecognised. */
private fun parseSelector(raw: String): CssSelector? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    var type: String? = null
    var id: String? = null
    val classes = ArrayList<String>()
    var i = 0
    var parts = 0
    while (i < text.length) {
        val marker = text[i]
        val prefix = if (marker == '#' || marker == '.') 1 else 0
        var end = i + prefix
        while (end < text.length && isIdentChar(text[end])) end++
        val name = text.substring(i + prefix, end)
        val star = prefix == 0 && end == i && marker == '*'
        if (star) end = i + 1
        if (end == i + prefix && !star) return null
        if (++parts > MAX_SELECTOR_COMPOUNDS) return null
        when {
            marker == '#' -> if (id != null) return null else id = name
            marker == '.' -> classes += name
            star -> if (i != 0) return null
            else -> if (i != 0) return null else type = name.lowercase()
        }
        i = end
    }
    return CssSelector(type, id, classes)
}

private fun isIdentChar(c: Char): Boolean = c.isLetterOrDigit() || c == '-' || c == '_' || c.code > 0x7F

/** The bold, italic and alignment a block of declarations (`a: b; c: d`) says; an unknown or malformed one is ignored. */
internal fun parseDeclarations(text: String): CssDecl {
    var bold: Boolean? = null
    var italic: Boolean? = null
    var align: BlockAlign? = null
    for (declaration in text.split(';')) {
        val colon = declaration.indexOf(':')
        if (colon <= 0) continue
        val name = declaration.substring(0, colon).trim().lowercase()
        val value = declaration.substring(colon + 1).substringBefore("!").trim().lowercase()
        when (name) {
            "font-weight" -> weightIsBold(value)?.let { bold = it }
            "font-style" -> when (value) {
                "italic", "oblique" -> italic = true
                "normal" -> italic = false
            }
            "text-align" -> alignOf(value)?.let { align = it }
        }
    }
    return CssDecl(bold, italic, align)
}

/** `bold` and 600 or more are bold, `normal` and up to 500 are not; `bolder` and `lighter` depend on the parent. */
private fun weightIsBold(value: String): Boolean? = when (value) {
    "bold" -> true
    "normal" -> false
    else -> value.toIntOrNull()?.let { if (it >= WEIGHT_BOLD) true else if (it <= WEIGHT_NORMAL_MAX) false else null }
}

/**
 * The text of every `<style>` in a chapter, found by a plain scan: no markup parse, never throws. This is what
 * lets a `<style>` after the text it styles still apply, without buffering the chapter in the parser.
 */
internal fun stylesIn(xhtml: ByteArray): String {
    val out = StringBuilder()
    var i = 0
    while (i < xhtml.size) {
        if (xhtml[i] != LT) {
            i++
            continue
        }
        if (startsWith(xhtml, i, "<!--")) {
            val end = indexOf(xhtml, "-->", i + 4, ignoreCase = false)
            if (end < 0) break
            i = end + 3
            continue
        }
        if (startsWith(xhtml, i + 1, "style", ignoreCase = true) && endsName(xhtml, i + 1 + "style".length)) {
            val open = indexOf(xhtml, ">", i, ignoreCase = false)
            if (open < 0) break
            if (xhtml[open - 1] == SLASH) {
                i = open + 1
                continue
            }
            val close = indexOf(xhtml, "</style", open + 1, ignoreCase = true)
            val end = if (close < 0) xhtml.size else close
            out.append(String(xhtml, open + 1, end - open - 1, Charsets.UTF_8)).append('\n')
            i = if (close < 0) xhtml.size else close + "</style".length
            continue
        }
        i++
    }
    return out.toString().replace("<![CDATA[", "").replace("]]>", "")
}

private const val LT = '<'.code.toByte()
private const val SLASH = '/'.code.toByte()

private fun endsName(bytes: ByteArray, at: Int): Boolean {
    if (at >= bytes.size) return false
    val c = bytes[at].toInt().toChar()
    return c == '>' || c == '/' || c.isWhitespace()
}

private fun startsWith(bytes: ByteArray, at: Int, token: String, ignoreCase: Boolean = false): Boolean {
    if (at < 0 || at + token.length > bytes.size) return false
    for (k in token.indices) {
        val b = bytes[at + k].toInt().toChar()
        if (!b.equals(token[k], ignoreCase)) return false
    }
    return true
}

private fun indexOf(bytes: ByteArray, token: String, from: Int, ignoreCase: Boolean): Int {
    var i = from
    while (i + token.length <= bytes.size) {
        if (startsWith(bytes, i, token, ignoreCase)) return i
        i++
    }
    return -1
}

/** The book's rules first and the chapter's own `<style>` rules after, so the chapter wins a tie. */
internal fun sheetFor(bookRules: List<CssRule>, xhtml: ByteArray): CssSheet {
    val own = stylesIn(xhtml)
    return CssSheet(if (own.isBlank()) bookRules else bookRules + parseCss(own, bookRules.size))
}
