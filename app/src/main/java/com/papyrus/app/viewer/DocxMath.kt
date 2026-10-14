package com.papyrus.app.viewer

/**
 * Writes one Office Math (OMML) equation as a line of text. Papyrus has no maths renderer and adds no
 * dependency for one, so an equation comes out the way it would be typed rather than typeset:
 * `(a+b)/c`, `x²`, `√(x+1)`, `∑_(i=1)^n i`. That is not Word's layout, but the equation is there to
 * read, where it used to be dropped without a trace.
 *
 * Fed by the caller's XML walk: [start] and [end] for each element and [text] for character data,
 * from the opening `m:oMath` until [end] returns the finished line at its close. Elements it does
 * not know contribute the text of their children, so an unfamiliar construct degrades to its words.
 */
internal class OmmlLinearizer {

    /** One open element. [parts] are its finished children that name a role (`m:num`, `m:e`, ...). */
    private class Node(val name: String) {
        val text = StringBuilder()
        val parts = ArrayList<Pair<String, String>>()
        val props = HashMap<String, String>()

        fun part(role: String): String = parts.firstOrNull { it.first == role }?.second.orEmpty()

        fun partsNamed(role: String): List<String> = parts.filter { it.first == role }.map { it.second }
    }

    private val stack = ArrayList<Node>()

    // Elements opened past [MAX_DEPTH] are not tracked, only counted, so their closes still pair up.
    private var untracked = 0
    private var written = 0

    /** True from the opening `m:oMath` to its close. */
    val active: Boolean get() = stack.isNotEmpty()

    /** [value] is the element's `m:val`, which settings such as `m:chr` carry. */
    fun start(name: String, value: String?) {
        if (name in SETTINGS) {
            // A setting belongs to the element whose `...Pr` it sits in (`m:chr` in `m:naryPr` in `m:nary`).
            stack.lastOrNull { !it.name.endsWith("Pr") }?.props?.put(name, value.orEmpty())
            return
        }
        if (untracked > 0 || stack.size >= MAX_DEPTH) {
            untracked++
            return
        }
        stack += Node(name)
    }

    fun text(value: String) {
        val top = stack.lastOrNull() ?: return
        if (untracked > 0 || top.name !in TEXT_ELEMENTS || written >= MAX_CHARS) return
        top.text.append(value)
        written += value.length
    }

    /** The equation, when [name] closes the outermost element; null while it is still open. */
    fun end(name: String): String? {
        if (name in SETTINGS) return null
        if (untracked > 0) {
            untracked--
            return null
        }
        if (stack.isEmpty()) return null
        val node = stack.removeAt(stack.lastIndex)
        val out = render(node)
        val parent = stack.lastOrNull() ?: return out.trim()
        if (node.name in ROLES) parent.parts += node.name to out else parent.text.append(out)
        return null
    }

    private fun render(node: Node): String = when (node.name) {
        "m:f" -> group(node.part("m:num")) + "/" + group(node.part("m:den"))
        "m:sSup" -> node.part("m:e") + script(node.part("m:sup"), up = true)
        "m:sSub" -> node.part("m:e") + script(node.part("m:sub"), up = false)
        "m:sSubSup" -> node.part("m:e") + script(node.part("m:sub"), up = false) + script(node.part("m:sup"), up = true)
        "m:sPre" -> script(node.part("m:sup"), up = true) + script(node.part("m:sub"), up = false) + node.part("m:e")
        "m:limLow" -> node.part("m:e") + script(node.part("m:lim"), up = false)
        "m:limUpp" -> node.part("m:e") + script(node.part("m:lim"), up = true)
        "m:rad" -> radical(node)
        "m:nary" -> nary(node)
        "m:d" -> delimited(node)
        "m:func" -> function(node)
        "m:acc" -> node.part("m:e") + (node.props["m:chr"] ?: COMBINING_CIRCUMFLEX)
        "m:mr" -> node.partsNamed("m:e").joinToString(" ")
        "m:m" -> node.partsNamed("m:mr").joinToString("; ", "[", "]")
        "m:eqArr" -> node.partsNamed("m:e").joinToString("; ")
        else -> node.text.toString() + node.parts.joinToString("") { it.second }
    }

    private fun radical(node: Node): String {
        val body = group(node.part("m:e"))
        return when (val degree = node.part("m:deg").trim()) {
            "" -> "\u221A$body"
            "3" -> "\u221B$body"
            "4" -> "\u221C$body"
            else -> script(degree, up = true) + "\u221A" + body
        }
    }

    /** `∑`, `∏`, `∫` and kin, with their limits; the integral sign is the default the format assumes. */
    private fun nary(node: Node): String {
        val symbol = node.props["m:chr"] ?: "\u222B"
        val limits = script(node.part("m:sub"), up = false) + script(node.part("m:sup"), up = true)
        val operand = node.part("m:e")
        return symbol + limits + if (operand.isEmpty()) "" else " $operand"
    }

    /** Brackets with their own characters; `m:d` holds one `m:e` per item, set apart by the separator. */
    private fun delimited(node: Node): String {
        val open = node.props["m:begChr"] ?: "("
        val close = node.props["m:endChr"] ?: ")"
        val separator = node.props["m:sepChr"] ?: "|"
        return node.partsNamed("m:e").joinToString(separator, open, close)
    }

    /** `sin x`, but `sin(x)` as typed when the argument already brings its own bracket. */
    private fun function(node: Node): String {
        val argument = node.part("m:e")
        val gap = if (argument.isEmpty() || argument.startsWith("(")) "" else " "
        return node.part("m:fName") + gap + argument
    }

    private companion object {
        /** Past this the rest of an equation is not tracked: a hostile file cannot nest without end. */
        const val MAX_DEPTH = 64
        const val MAX_CHARS = 20_000

        /** Elements that hold characters. `w:t` is how a run of ordinary text inside an equation is written. */
        val TEXT_ELEMENTS = setOf("m:t", "w:t")

        /** Settings read from `m:val`: leaves, never opened as elements of their own. */
        val SETTINGS = setOf("m:chr", "m:begChr", "m:endChr", "m:sepChr")

        /** Children that fill a named slot of their parent, rather than adding text to it. */
        val ROLES = setOf(
            "m:e", "m:num", "m:den", "m:sup", "m:sub", "m:deg", "m:lim", "m:fName", "m:mr",
        )

        const val COMBINING_CIRCUMFLEX = "\u0302"

        // Same length, pairwise: the character in PLAIN has its raised/lowered form at the same index.
        // U+2212 is the minus sign Word writes in equations, as against the hyphen.
        const val PLAIN_SUPERSCRIPTABLE = "0123456789+-\u2212=()ni"
        const val SUPERSCRIPTS = "\u2070\u00B9\u00B2\u00B3\u2074\u2075\u2076\u2077\u2078\u2079\u207A\u207B\u207B\u207C\u207D\u207E\u207F\u2071"
        const val PLAIN_SUBSCRIPTABLE = "0123456789+-\u2212=()"
        const val SUBSCRIPTS = "\u2080\u2081\u2082\u2083\u2084\u2085\u2086\u2087\u2088\u2089\u208A\u208B\u208B\u208C\u208D\u208E"

        val SUPER_MAP: Map<Char, Char> = PLAIN_SUPERSCRIPTABLE.zip(SUPERSCRIPTS).toMap()
        val SUB_MAP: Map<Char, Char> = PLAIN_SUBSCRIPTABLE.zip(SUBSCRIPTS).toMap()

        /** One word or number reads as a unit; anything with an operator or a space needs brackets to stay one. */
        fun group(part: String): String =
            if (part.length <= 1 || part.all { it.isLetterOrDigit() || it == '.' }) part else "($part)"

        /**
         * A raised or lowered part: the real character where there is one (`x²`), else `^`/`_` and
         * brackets (`x^(n+1)`), since a half-converted exponent would read as a different number.
         */
        fun script(part: String, up: Boolean): String {
            if (part.isEmpty()) return ""
            val map = if (up) SUPER_MAP else SUB_MAP
            val converted = StringBuilder()
            for (ch in part) converted.append(map[ch] ?: return (if (up) "^" else "_") + group(part))
            return converted.toString()
        }
    }
}
