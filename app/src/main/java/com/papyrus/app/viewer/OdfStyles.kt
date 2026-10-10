package com.papyrus.app.viewer

import org.xmlpull.v1.XmlPullParser

private fun XmlPullParser.attr(name: String): String? = getAttributeValue(null, name)

private const val BOLD_WEIGHT = 600
private const val DEFAULT_WEIGHT = 400
private const val MAX_STYLE_CHAIN = 20
private const val MAX_HEADING_LEVEL = 6

/** Applies a `style:text-properties` element to this layer. An attribute not present changes nothing. */
internal fun RunProps.applyOdf(p: XmlPullParser) {
    p.attr("fo:font-weight")?.let { bold = it == "bold" || (it.toIntOrNull() ?: DEFAULT_WEIGHT) >= BOLD_WEIGHT }
    p.attr("fo:font-style")?.let { italic = it == "italic" || it == "oblique" }
    p.attr("style:text-underline-style")?.let { underline = it != "none" }
    p.attr("style:text-line-through-style")?.let { strike = it != "none" }
    p.attr("style:text-position")?.let { script = scriptOf(it) }
    p.attr("fo:color")?.let { color = parseHexColor(it) }
    p.attr("fo:background-color")?.let { background = if (it == "transparent") COLOR_AUTO else parseHexColor(it) }
    (p.attr("style:font-name") ?: p.attr("fo:font-family"))?.let { mono = isMonoFont(it) }
}

/** "super 58%", "sub", or a signed percentage ("33%", "-33% 58%"): positive raises, negative lowers. */
private fun scriptOf(value: String): ScriptShift {
    val head = value.trim().substringBefore(' ')
    return when {
        head.startsWith("super") -> ScriptShift.SUPER
        head.startsWith("sub") -> ScriptShift.SUB
        else -> {
            val percent = head.removeSuffix("%").toIntOrNull() ?: 0
            when {
                percent > 0 -> ScriptShift.SUPER
                percent < 0 -> ScriptShift.SUB
                else -> ScriptShift.NONE
            }
        }
    }
}

private fun odfAlign(value: String?): BlockAlign? = alignOf(value)

/** ODF `style:num-format` to the OOXML names [formatListNumber] understands. */
private fun odfNumFormat(value: String?): String = when (value) {
    "a" -> "lowerLetter"
    "A" -> "upperLetter"
    "i" -> "lowerRoman"
    "I" -> "upperRoman"
    else -> "decimal"
}

/** One `style:style`. Properties are what the style says itself; inheritance is [OdfStyles]'s job. */
internal class OdfStyle(val name: String, val family: String) {
    var parent: String? = null
    val runProps = RunProps()
    var align: BlockAlign? = null
    var marginLeftTwips: Int? = null

    /** `style:table-cell-properties`: the cell's fill. */
    var background: Int? = null
}

/** One level of a `text:list-style`. */
internal class OdfListLevel(
    val bullet: Boolean,
    val bulletChar: String,
    val format: String,
    val prefix: String,
    val suffix: String,
    val start: Int,
    val displayLevels: Int,
)

/**
 * Every style the document defines, from `styles.xml` and the automatic styles in `content.xml`,
 * keyed by family and name: "P1" the paragraph style and "P1" the table style differ.
 */
internal class OdfStyles {
    val styles = HashMap<String, OdfStyle>()
    val listStyles = HashMap<String, Map<Int, OdfListLevel>>()
    val defaultRun = RunProps()
    private val paragraphRunCache = HashMap<String, RunProps>()

    private fun chain(family: String, name: String?): List<OdfStyle> {
        val out = ArrayList<OdfStyle>()
        var current: OdfStyle? = name?.let { styles["$family:$it"] }
        var guard = 0
        while (current != null && guard++ < MAX_STYLE_CHAIN) {
            out += current
            current = current.parent?.let { styles["$family:$it"] }
        }
        out.reverse()
        return out
    }

    /** Character formatting a paragraph gives its text: the default style, then the style chain. */
    fun paragraphRun(name: String?): RunProps = paragraphRunCache.getOrPut(name.orEmpty()) {
        var props = defaultRun
        for (style in chain("paragraph", name)) props = style.runProps.mergedOver(props)
        props
    }

    /** What a `text:span` style adds on top of the paragraph's. */
    fun textRun(name: String?): RunProps {
        var props = RunProps()
        for (style in chain("text", name)) props = style.runProps.mergedOver(props)
        return props
    }

    fun paragraphAlign(name: String?): BlockAlign? {
        var align: BlockAlign? = null
        for (style in chain("paragraph", name)) style.align?.let { align = it }
        return align
    }

    fun paragraphIndentTwips(name: String?): Int? {
        var indent: Int? = null
        for (style in chain("paragraph", name)) style.marginLeftTwips?.let { indent = it }
        return indent
    }

    fun cellBackground(name: String?): Int? {
        var fill: Int? = null
        for (style in chain("table-cell", name)) style.background?.let { fill = it }
        return fill
    }

    /**
     * 0 for body text. `text:h` is a heading by element; this is for a `text:p` whose style, or one it
     * inherits from, is the built-in Title or "Heading N" (names encode a space as `_20_`).
     */
    fun headingLevel(name: String?): Int {
        for (style in chain("paragraph", name)) {
            val decoded = style.name.replace("_20_", " ")
            if (decoded == "Title") return 1
            val digit = HEADING_NAME.matchEntire(decoded)?.groupValues?.get(1)?.toIntOrNull()
            if (digit != null) return digit.coerceAtMost(MAX_HEADING_LEVEL)
        }
        return 0
    }

    fun listLevels(name: String?): Map<Int, OdfListLevel>? = name?.let { listStyles[it] }

    private companion object {
        val HEADING_NAME = Regex("^Heading ([1-9])$")
    }
}

/** Reads the style definitions from [xml] into [sheet]; stops at the document body, which holds none. */
internal fun readOdfStyles(xml: ByteArray, sheet: OdfStyles, newParser: ParserFactory) {
    OdfStylesReader(newParser(xml.inputStream()), sheet).read()
}

private class OdfStylesReader(private val p: XmlPullParser, private val sheet: OdfStyles) {
    private var current: OdfStyle? = null
    private var inDefaultParagraph = false
    private var listName: String? = null
    private var listLevels: MutableMap<Int, OdfListLevel>? = null

    fun read() {
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                if (p.name == "office:body") return
                onStart()
            } else if (event == XmlPullParser.END_TAG) {
                onEnd()
            }
            event = p.next()
        }
    }

    private fun onStart() {
        when (p.name) {
            "style:style" -> {
                val name = p.attr("style:name")
                val family = p.attr("style:family")
                if (name != null && family != null) {
                    current = OdfStyle(name, family).also { it.parent = p.attr("style:parent-style-name") }
                }
            }
            "style:default-style" -> inDefaultParagraph = p.attr("style:family") == "paragraph"
            "style:text-properties" -> {
                val target = current?.runProps ?: if (inDefaultParagraph) sheet.defaultRun else null
                target?.applyOdf(p)
            }
            "style:paragraph-properties" -> current?.let { style ->
                odfAlign(p.attr("fo:text-align"))?.let { style.align = it }
                lengthToTwips(p.attr("fo:margin-left"))?.let { style.marginLeftTwips = it }
            }
            "style:table-cell-properties" -> current?.let { style ->
                val fill = p.attr("fo:background-color")
                if (fill != null) style.background = if (fill == "transparent") null else parseHexColor(fill)
            }
            "text:list-style" -> {
                listName = p.attr("style:name")
                listLevels = HashMap()
            }
            "text:list-level-style-bullet", "text:list-level-style-number", "text:list-level-style-image" -> addLevel()
        }
    }

    private fun addLevel() {
        val levels = listLevels ?: return
        val level = p.attr("text:level")?.toIntOrNull() ?: return
        levels[level] = OdfListLevel(
            bullet = p.name != "text:list-level-style-number",
            bulletChar = p.attr("text:bullet-char").orEmpty(),
            format = odfNumFormat(p.attr("style:num-format")),
            prefix = p.attr("style:num-prefix").orEmpty(),
            suffix = p.attr("style:num-suffix").orEmpty(),
            start = p.attr("text:start-value")?.toIntOrNull() ?: 1,
            displayLevels = p.attr("text:display-levels")?.toIntOrNull() ?: 1,
        )
    }

    private fun onEnd() {
        when (p.name) {
            "style:style" -> {
                current?.let { sheet.styles["${it.family}:${it.name}"] = it }
                current = null
            }
            "style:default-style" -> inDefaultParagraph = false
            "text:list-style" -> {
                val name = listName
                val levels = listLevels
                if (name != null && levels != null) sheet.listStyles[name] = levels
                listName = null
                listLevels = null
            }
        }
    }
}
