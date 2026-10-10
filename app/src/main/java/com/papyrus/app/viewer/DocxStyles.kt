package com.papyrus.app.viewer

import org.xmlpull.v1.XmlPullParser

/** True unless the element says `w:val="0"`, "false" or "off": a bare `<w:b/>` means on. */
private fun XmlPullParser.onOff(): Boolean {
    val value = getAttributeValue(null, "w:val")
    return value == null || !(value == "0" || value == "false" || value == "off")
}

private fun XmlPullParser.attr(name: String): String? = getAttributeValue(null, name)

private fun XmlPullParser.intAttr(name: String): Int? = getAttributeValue(null, name)?.toIntOrNull()

/** Applies one child element of a `w:rPr` to this layer. Anything it does not know is ignored. */
internal fun RunProps.applyDocx(p: XmlPullParser) {
    when (p.name) {
        "w:b" -> bold = p.onOff()
        "w:i" -> italic = p.onOff()
        "w:u" -> underline = p.attr("w:val") != "none"
        "w:strike", "w:dstrike" -> strike = p.onOff()
        "w:vertAlign" -> script = when (p.attr("w:val")) {
            "superscript" -> ScriptShift.SUPER
            "subscript" -> ScriptShift.SUB
            else -> ScriptShift.NONE
        }
        "w:color" -> {
            val value = p.attr("w:val")
            color = if (value == "auto") COLOR_AUTO else parseHexColor(value)
        }
        "w:highlight" -> {
            val value = p.attr("w:val")
            background = if (value == "none") COLOR_AUTO else highlightColor(value)
        }
        "w:shd" -> {
            val fill = p.attr("w:fill")
            if (fill != null && fill != "auto") background = parseHexColor(fill)
        }
        "w:rFonts" -> {
            val font = p.attr("w:ascii") ?: p.attr("w:hAnsi")
            if (font != null) mono = isMonoFont(font)
        }
    }
}

/** One `w:style`. Properties are what the style says itself; inheritance is [DocxStyles]'s job. */
internal class DocxStyle(val id: String, val type: String, val isDefault: Boolean) {
    var name: String = ""
    var basedOn: String? = null
    val runProps = RunProps()
    var align: BlockAlign? = null
    var indentTwips: Int? = null

    /** `w:outlineLvl`, 0-based: 0 is a first-level heading, 9 is body text. */
    var outline: Int? = null
    var numId: Int? = null
    var numLevel: Int? = null
}

/** A paragraph style with its whole `basedOn` chain folded in. */
internal class ParaStyle(
    val align: BlockAlign?,
    val indentTwips: Int?,
    val outline: Int?,
    /** Heading level from the style's *name* ("heading 2", "Title"), or 0. */
    val namedHeading: Int,
    val numId: Int?,
    val numLevel: Int?,
)

private val HEADING_NAME = Regex("^heading ([1-9])$", RegexOption.IGNORE_CASE)

private const val MAX_STYLE_CHAIN = 20
private const val MAX_HEADING_LEVEL = 6
private const val LAST_HEADING_OUTLINE = 5

private fun namedHeading(name: String): Int {
    if (name.equals("title", ignoreCase = true)) return 1
    val digit = HEADING_NAME.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull() ?: return 0
    return digit.coerceAtMost(MAX_HEADING_LEVEL)
}

/**
 * What a style id used to mean before `styles.xml` was read: "Heading2" or "Title". The fallback for
 * a document with no styles part, or an id the part does not define.
 */
internal fun styleIdHeadingLevel(id: String): Int = when {
    id.equals("Title", true) -> 1
    id.startsWith("Heading", true) -> id.substringAfter("Heading", "1").toIntOrNull()?.coerceIn(1, MAX_HEADING_LEVEL) ?: 1
    else -> 0
}

internal class DocxStyles(
    private val styles: Map<String, DocxStyle>,
    private val defaults: RunProps,
    private val defaultParagraph: String?,
) {
    private val runCache = HashMap<String, RunProps>()
    private val paragraphCache = HashMap<String, ParaStyle>()

    /** [id]'s style and everything it is based on, the root first. */
    private fun chain(id: String?): List<DocxStyle> {
        val out = ArrayList<DocxStyle>()
        var current: DocxStyle? = id?.let { styles[it] }
        var guard = 0
        while (current != null && guard++ < MAX_STYLE_CHAIN) {
            out += current
            current = current.basedOn?.let { styles[it] }
        }
        out.reverse()
        return out
    }

    /**
     * Character formatting a run gets before its own: document defaults, then the paragraph's style
     * chain, then the run's character style chain. Cached, and never mutated.
     */
    fun runProps(paragraphStyle: String?, characterStyle: String?): RunProps {
        val key = "${paragraphStyle.orEmpty()}|${characterStyle.orEmpty()}"
        return runCache.getOrPut(key) {
            var props = defaults
            for (style in chain(paragraphStyle ?: defaultParagraph)) props = style.runProps.mergedOver(props)
            for (style in chain(characterStyle)) props = style.runProps.mergedOver(props)
            props
        }
    }

    fun paragraph(styleId: String?): ParaStyle = paragraphCache.getOrPut(styleId.orEmpty()) {
        var align: BlockAlign? = null
        var indent: Int? = null
        var outline: Int? = null
        var numId: Int? = null
        var numLevel: Int? = null
        var named = 0
        for (style in chain(styleId ?: defaultParagraph)) {
            style.align?.let { align = it }
            style.indentTwips?.let { indent = it }
            style.outline?.let { outline = it }
            style.numId?.let { numId = it }
            style.numLevel?.let { numLevel = it }
            val heading = namedHeading(style.name)
            if (heading > 0) named = heading
        }
        ParaStyle(align, indent, outline, named, numId, numLevel)
    }

    /**
     * 0 for body text. An outline level says it outright, for any style a document invents; a
     * built-in heading's name says it where the part carries no level; the id spelling is the last resort.
     */
    fun headingLevel(styleId: String?, directOutline: Int?): Int {
        val style = paragraph(styleId)
        val outline = directOutline ?: style.outline
        if (outline != null) return if (outline in 0..LAST_HEADING_OUTLINE) outline + 1 else 0
        if (style.namedHeading > 0) return style.namedHeading
        return if (styleId != null && styles[styleId] == null) styleIdHeadingLevel(styleId) else 0
    }

    companion object {
        fun empty() = DocxStyles(emptyMap(), RunProps(), null)
    }
}

internal fun readDocxStyles(xml: ByteArray?, newParser: ParserFactory): DocxStyles {
    if (xml == null || xml.isEmpty()) return DocxStyles.empty()
    return DocxStylesReader(newParser(xml.inputStream())).read()
}

private class DocxStylesReader(private val p: XmlPullParser) {
    private val styles = HashMap<String, DocxStyle>()
    private val defaults = RunProps()
    private var defaultParagraph: String? = null
    private var current: DocxStyle? = null
    private var inDocDefaults = false
    private var inPPr = false
    private var inRPr = false

    /** Inside `w:tblStylePr`: formatting for one part of a table, not the style itself. */
    private var tableStyleDepth = 0

    /** Inside a `...PrChange`: the *previous* properties of a tracked change. */
    private var changeDepth = 0

    fun read(): DocxStyles {
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> onStart()
                XmlPullParser.END_TAG -> onEnd()
            }
            event = p.next()
        }
        return DocxStyles(styles, defaults, defaultParagraph)
    }

    private fun onStart() {
        val name = p.name
        when {
            name == "w:tblStylePr" -> tableStyleDepth++
            tableStyleDepth > 0 -> Unit
            name.endsWith("PrChange") -> changeDepth++
            changeDepth > 0 -> Unit
            name == "w:docDefaults" -> inDocDefaults = true
            name == "w:style" -> {
                val default = p.attr("w:default")
                current = DocxStyle(
                    id = p.attr("w:styleId").orEmpty(),
                    type = p.attr("w:type").orEmpty(),
                    isDefault = default == "1" || default == "true",
                )
            }
            name == "w:name" -> current?.name = p.attr("w:val").orEmpty()
            name == "w:basedOn" -> current?.basedOn = p.attr("w:val")
            name == "w:pPr" -> inPPr = true
            // A paragraph mark's own run properties sit inside w:pPr and describe the mark, not the text.
            name == "w:rPr" -> if (!inPPr) inRPr = true
            inPPr -> onParagraphProperty(name)
            inRPr -> (current?.runProps ?: if (inDocDefaults) defaults else null)?.applyDocx(p)
        }
    }

    private fun onParagraphProperty(name: String) {
        val style = current ?: return
        when (name) {
            "w:jc" -> style.align = alignOf(p.attr("w:val"))
            "w:ind" -> style.indentTwips = (p.attr("w:left") ?: p.attr("w:start"))?.toIntOrNull()
            "w:outlineLvl" -> style.outline = p.intAttr("w:val")
            "w:numId" -> style.numId = p.intAttr("w:val")
            "w:ilvl" -> style.numLevel = p.intAttr("w:val")
        }
    }

    private fun onEnd() {
        val name = p.name
        when {
            name == "w:tblStylePr" -> tableStyleDepth--
            tableStyleDepth > 0 -> Unit
            name.endsWith("PrChange") -> changeDepth--
            changeDepth > 0 -> Unit
            name == "w:docDefaults" -> inDocDefaults = false
            name == "w:style" -> {
                current?.let { style ->
                    if (style.id.isNotEmpty()) styles[style.id] = style
                    if (style.isDefault && style.type == "paragraph") defaultParagraph = style.id
                }
                current = null
            }
            name == "w:pPr" -> inPPr = false
            name == "w:rPr" -> inRPr = false
        }
    }
}

/** One `w:lvl` of a numbering definition. [symbolFont]: the bullet is a Symbol or Wingdings glyph. */
internal class NumLevel(val format: String, val text: String, val start: Int, val symbolFont: Boolean)

internal class DocxNumbering(
    private val abstracts: Map<Int, Map<Int, NumLevel>>,
    private val nums: Map<Int, Int>,
    private val startOverrides: Map<Int, Map<Int, Int>>,
) {
    fun abstractId(numId: Int): Int? = nums[numId]

    fun level(numId: Int, ilvl: Int): NumLevel? = nums[numId]?.let { abstracts[it]?.get(ilvl) }

    /** Levels this list instance restarts at a given number the first time it is used. */
    fun startOverride(numId: Int): Map<Int, Int> = startOverrides[numId].orEmpty()

    companion object {
        val EMPTY = DocxNumbering(emptyMap(), emptyMap(), emptyMap())
    }
}

internal fun readDocxNumbering(xml: ByteArray?, newParser: ParserFactory): DocxNumbering {
    if (xml == null || xml.isEmpty()) return DocxNumbering.EMPTY
    return DocxNumberingReader(newParser(xml.inputStream())).read()
}

private class DocxNumberingReader(private val p: XmlPullParser) {
    private val abstracts = HashMap<Int, MutableMap<Int, NumLevel>>()
    private val nums = HashMap<Int, Int>()
    private val overrides = HashMap<Int, MutableMap<Int, Int>>()

    private var abstractId: Int? = null
    private var level: Int? = null
    private var format = "decimal"
    private var text = ""
    private var start = 1
    private var symbol = false

    private var numId: Int? = null
    private var overrideLevel: Int? = null

    fun read(): DocxNumbering {
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> onStart()
                XmlPullParser.END_TAG -> onEnd()
            }
            event = p.next()
        }
        return DocxNumbering(abstracts, nums, overrides)
    }

    private fun onStart() {
        when (p.name) {
            "w:abstractNum" -> abstractId = p.intAttr("w:abstractNumId")
            // Only inside an abstractNum: a w:lvl inside a w:num's override is a replacement, not read here.
            "w:lvl" -> if (abstractId != null) {
                level = p.intAttr("w:ilvl")
                format = "decimal"
                text = ""
                start = 1
                symbol = false
            }
            "w:start" -> if (level != null) start = p.intAttr("w:val") ?: 1
            "w:numFmt" -> if (level != null) format = p.attr("w:val") ?: "decimal"
            "w:lvlText" -> if (level != null) text = p.attr("w:val").orEmpty()
            "w:rFonts" -> if (level != null) {
                val font = (p.attr("w:ascii") ?: p.attr("w:hAnsi"))?.lowercase().orEmpty()
                if (font.contains("symbol") || font.contains("wingdings")) symbol = true
            }
            "w:num" -> numId = p.intAttr("w:numId")
            "w:abstractNumId" -> {
                val id = numId
                val target = p.intAttr("w:val")
                if (id != null && target != null) nums[id] = target
            }
            "w:lvlOverride" -> overrideLevel = p.intAttr("w:ilvl")
            "w:startOverride" -> {
                val id = numId
                val lvl = overrideLevel
                if (id != null && lvl != null) {
                    overrides.getOrPut(id) { HashMap() }[lvl] = p.intAttr("w:val") ?: 1
                }
            }
        }
    }

    private fun onEnd() {
        when (p.name) {
            "w:lvl" -> {
                val owner = abstractId
                val lvl = level
                if (owner != null && lvl != null) {
                    abstracts.getOrPut(owner) { HashMap() }[lvl] = NumLevel(format, text, start, symbol)
                }
                level = null
            }
            "w:abstractNum" -> abstractId = null
            "w:num" -> {
                numId = null
                overrideLevel = null
            }
            "w:lvlOverride" -> overrideLevel = null
        }
    }
}

private const val MAX_LIST_LEVELS = 9
private const val UNSET = Int.MIN_VALUE

/**
 * Where each list has got to, in reading order. Counters belong to the *abstract* list, not a
 * `w:num`, because Word continues one numbering across every `w:num` sharing a definition.
 */
internal class ListCounters(private val numbering: DocxNumbering) {
    private val counts = HashMap<Int, IntArray>()
    private val started = HashSet<Int>()

    /** The marker for the next paragraph of [numId] at [ilvl]; null if the list is not defined. */
    fun next(numId: Int, ilvl: Int): String? {
        val abstractId = numbering.abstractId(numId) ?: return null
        val level = ilvl.coerceIn(0, MAX_LIST_LEVELS - 1)
        val definition = numbering.level(numId, level) ?: return null
        val values = counts.getOrPut(abstractId) { IntArray(MAX_LIST_LEVELS) { UNSET } }
        if (started.add(numId)) {
            numbering.startOverride(numId).forEach { (overridden, startAt) ->
                if (overridden in 0 until MAX_LIST_LEVELS) values[overridden] = startAt - 1
            }
        }
        values[level] = if (values[level] == UNSET) definition.start else values[level] + 1
        // A new item at this level starts every deeper level over.
        for (deeper in level + 1 until MAX_LIST_LEVELS) values[deeper] = UNSET
        return marker(numId, definition, level, values)
    }

    private fun marker(numId: Int, definition: NumLevel, level: Int, values: IntArray): String {
        if (definition.format == "bullet") {
            return displayBullet(if (definition.symbolFont) null else definition.text, level)
        }
        if (definition.format == "none") return ""
        val out = StringBuilder()
        val template = definition.text
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '%' && i + 1 < template.length && template[i + 1].isDigit()) {
                val index = template[i + 1] - '1'
                if (index in 0 until MAX_LIST_LEVELS) {
                    val referenced = numbering.level(numId, index)
                    val value = if (values[index] == UNSET) referenced?.start ?: 1 else values[index]
                    out.append(formatListNumber(value, referenced?.format ?: "decimal"))
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
