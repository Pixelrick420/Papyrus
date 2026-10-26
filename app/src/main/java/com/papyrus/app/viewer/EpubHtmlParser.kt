package com.papyrus.app.viewer

import org.xmlpull.v1.XmlPullParser
import java.io.IOException

/** One chapter cannot be read (malformed, or nested past the limit); the book goes on without it. */
internal class EpubChapterException(message: String) : IOException(message)

/**
 * Where a link's target sits: the block that was next to be emitted when the element began ([block], before
 * any image went in), and whether the element is itself an image, which the media pass places differently.
 */
internal class Anchor(val block: Int, val onImage: Boolean)

/**
 * Where chapters put what they read. [blocks] is the book's one list, [pending] the images seen, to be
 * placed by the shared media pass, [anchors] the places a link can name. A block past
 * [EpubLimits.maxBlocks] is dropped and [truncated] set, which is how a limit reaches the reader as the
 * incomplete-document notice instead of silence.
 */
internal class EpubSink(
    val blocks: MutableList<OfficeBlock>,
    val pending: MutableList<PendingImage>,
    val limits: EpubLimits,
) {
    var truncated = false

    val anchors = HashMap<BookLink, Anchor>()

    /** The anchors in the order they were added, so a chapter that fails can take its own back out. */
    private val anchorTrail = ArrayList<BookLink>()

    val full: Boolean get() = blocks.size >= limits.maxBlocks

    /** The first element to name a place wins, as in a browser; past [EpubLimits.maxAnchors] none is kept. */
    fun anchor(link: BookLink, onImage: Boolean = false) {
        if (anchors.size >= limits.maxAnchors || link in anchors) return
        anchors[link] = Anchor(blocks.size, onImage)
        anchorTrail += link
    }

    fun anchorMark(): Int = anchorTrail.size

    fun rollBackAnchors(mark: Int) {
        while (anchorTrail.size > mark) anchors.remove(anchorTrail.removeAt(anchorTrail.lastIndex))
    }

    fun add(block: OfficeBlock) {
        if (full) {
            truncated = true
            return
        }
        blocks += block
    }
}

/** HTML whitespace: the five characters a browser collapses. A no-break space is not one of them. */
internal fun isHtmlSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000C'

private const val MARK_BACKGROUND = 0xFFF59D
private const val MAX_LIST_LEVEL = 8
private const val MAX_SPAN = 64
private const val MAX_IMAGE_WIDTH_DP = 4_000f
private const val MAX_ALT_CHARS = 200
private const val MAX_QUOTE_INDENT = 6
private val HEADING_LEVEL = mapOf("h1" to 1, "h2" to 2, "h3" to 3, "h4" to 4, "h5" to 5, "h6" to 6)

/** Whole subtrees that are not reading matter: code, styling, embedded players and the document head. */
private val IGNORED = setOf(
    "script", "style", "iframe", "object", "embed", "head", "title", "template", "audio", "video", "canvas",
    "svg", "rt", "rp", "applet", "noscript", "select", "datalist",
)

/** Elements that end the paragraph before them and start a new one. */
private val BLOCKS = setOf(
    "p", "div", "section", "article", "header", "footer", "aside", "main", "figure", "figcaption", "address",
    "details", "summary", "dl", "dt", "dd", "center", "fieldset", "form", "caption", "nav", "hgroup", "body",
)

/** OOXML `numFmt` names, which [formatListNumber] speaks, for an `ol`'s `type`. */
private val LIST_TYPES = mapOf("a" to "lowerLetter", "A" to "upperLetter", "i" to "lowerRoman", "I" to "upperRoman")

/**
 * One XHTML chapter as [OfficeBlock]s, appended to a sink. The platform pull parser reads the markup as XML
 * (so it must be well formed; HTML's named entities are taught to it first, see [EpubEntities]) and this
 * class keeps every stack it needs by hand, so a hostile nesting costs memory it bounds and never the call
 * stack. Reading is for text: the document's own stylesheet is never read, nothing is fetched, and scripts,
 * players, frames and hidden elements are skipped as whole subtrees. A link to another place in the book
 * becomes a [BookLink], and every element with an `id` is noted in [EpubSink.anchors]. One instance reads one chapter.
 */
internal class EpubHtmlParser(
    private val factory: ParserFactory,
    private val sink: EpubSink,
) {
    private class Frame(val tag: String) {
        var restore: CharFormat? = null
        var previousHeading = 0
        var setsDirection = false
        var previousDirection: BlockDirection? = null
    }

    private class ListFrame(val ordered: Boolean, var next: Int, val numberFormat: String)

    private class ItemFrame(val level: Int, var marker: String?)

    private class TableState {
        val rows = ArrayList<List<OfficeCell>>()
        var row: ArrayList<OfficeCell>? = null
        var column = 0

        /** Column to how many more rows a cell above still covers it. */
        val covered = HashMap<Int, Int>()
    }

    private class CellState(val column: Int, val colspan: Int, val rowspan: Int) {
        val builder = RichTextBuilder()
        var atLineStart = true
    }

    private val builder = RichTextBuilder()
    private var format = CharFormat.PLAIN
    private val frames = ArrayList<Frame>()
    private var ignoreDepth = 0
    private var ignoringSvg = false
    private var lastSpace = true
    private var headingLevel = 0
    private var quoteDepth = 0
    private var preDepth = 0
    private val lists = ArrayList<ListFrame>()
    private val items = ArrayList<ItemFrame>()
    private var tableDepth = 0
    private var table: TableState? = null
    private var cell: CellState? = null
    private var chapter = ""
    private var direction: BlockDirection? = null

    /** Reads [bytes], a chapter at [chapterPath], whose relative references resolve against its own directory. */
    fun parse(bytes: ByteArray, chapterPath: String) {
        chapter = chapterPath
        val dir = dirOf(chapterPath)
        val parser = factory(bytes.inputStream())
        EpubEntities.defineAll(parser)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (sink.full) {
                sink.truncated = true
                return
            }
            when (event) {
                XmlPullParser.START_TAG -> start(parser, dir)
                XmlPullParser.END_TAG -> end()
                XmlPullParser.TEXT -> text(parser.text)
            }
            event = parser.next()
        }
        // Some parsers call a file that stops inside an element finished; a chapter cut short is not whole.
        if (frames.isNotEmpty() || ignoreDepth > 0) throw EpubChapterException("document ended inside an element")
        flushBlock()
    }

    private fun start(p: XmlPullParser, dir: String) {
        val tag = p.name.substringAfter(':').lowercase()
        if (ignoreDepth > 0) {
            ignoreDepth++
            if (ignoringSvg && tag == "image") image(attribute(p, "href"), null, null, dir)
            return
        }
        if (tag in IGNORED || isHidden(p)) {
            ignoreDepth = 1
            ignoringSvg = tag == "svg"
            return
        }
        if (frames.size >= sink.limits.maxDepth) throw EpubChapterException("nested too deeply")
        val frame = Frame(tag)
        frames += frame
        when {
            tag in HEADING_LEVEL -> {
                flushBlock()
                frame.previousHeading = headingLevel
                headingLevel = HEADING_LEVEL.getValue(tag)
                captureAnchor(p, tag)
            }
            tag in BLOCKS -> {
                flushBlock()
                captureAnchor(p, tag)
            }
            else -> {
                startOther(p, tag, frame, dir)
                captureAnchor(p, tag)
            }
        }
        // After the branch, which may have ended the block before this element: that one is not in it.
        applyDirection(p, frame)
    }

    /**
     * `dir` on any element (`html`, a `div`, a `bdo`) sets the direction of the blocks inside it until the
     * element ends; `auto` would need a look at the text, so it leaves what was inherited alone.
     */
    private fun applyDirection(p: XmlPullParser, frame: Frame) {
        val next = when (p.getAttributeValue(null, "dir")?.trim()?.lowercase()) {
            "rtl" -> BlockDirection.RTL
            "ltr" -> BlockDirection.LTR
            else -> return
        }
        frame.setsDirection = true
        frame.previousDirection = direction
        direction = next
    }

    /**
     * Notes the place this element names with its `id` (an `<a>` may use `name` as well) as the block about to
     * be emitted, which is where what the element opens will land: the paragraph around a `span`, the first
     * block of a `div`, an image's own slot. An element in a skipped subtree never gets here.
     */
    private fun captureAnchor(p: XmlPullParser, tag: String) {
        val names = if (tag == "a") listOf("id", "name") else listOf("id")
        for (name in names) {
            val value = p.getAttributeValue(null, name)?.trim().orEmpty()
            if (value.isNotEmpty()) sink.anchor(BookLink(chapter, value), onImage = tag == "img")
        }
    }

    private fun startOther(p: XmlPullParser, tag: String, frame: Frame, dir: String) {
        when (tag) {
            "ul", "ol" -> {
                flushBlock()
                val ordered = tag == "ol"
                val start = p.getAttributeValue(null, "start")?.trim()?.toIntOrNull() ?: 1
                lists += ListFrame(ordered, start, LIST_TYPES[p.getAttributeValue(null, "type")?.trim()] ?: "decimal")
            }
            "li" -> startItem(p)
            "blockquote" -> {
                flushBlock()
                quoteDepth++
            }
            "pre" -> {
                flushBlock()
                preDepth++
                pushFormat(frame, format.copy(mono = true))
            }
            "br" -> lineBreak()
            "hr" -> {
                flushBlock()
                if (cell == null) sink.add(OfficeBlock.Divider)
            }
            "img" -> image(
                p.getAttributeValue(null, "src"),
                p.getAttributeValue(null, "alt") ?: p.getAttributeValue(null, "title"),
                p.getAttributeValue(null, "width"),
                dir,
            )
            "table" -> startTable()
            "tr" -> startRow()
            "td", "th" -> startCell(p, tag == "th", frame)
            else -> inlineFormat(tag, p, dir)?.let { pushFormat(frame, it) }
        }
    }

    private fun end() {
        if (ignoreDepth > 0) {
            ignoreDepth--
            if (ignoreDepth == 0) ignoringSvg = false
            return
        }
        if (frames.isEmpty()) return
        val frame = frames.removeAt(frames.lastIndex)
        frame.restore?.let { format = it }
        val tag = frame.tag
        when {
            tag in HEADING_LEVEL -> {
                flushBlock()
                headingLevel = frame.previousHeading
            }
            tag in BLOCKS -> flushBlock()
            else -> endOther(tag)
        }
        // Last: the block this element closed is emitted above, still in this element's direction.
        if (frame.setsDirection) direction = frame.previousDirection
    }

    private fun endOther(tag: String) {
        when (tag) {
            "ul", "ol" -> {
                flushBlock()
                if (lists.isNotEmpty()) lists.removeAt(lists.lastIndex)
            }
            "li" -> {
                flushBlock()
                if (items.isNotEmpty()) items.removeAt(items.lastIndex)
            }
            "blockquote" -> {
                flushBlock()
                quoteDepth = (quoteDepth - 1).coerceAtLeast(0)
            }
            "pre" -> {
                flushBlock()
                preDepth = (preDepth - 1).coerceAtLeast(0)
            }
            "table" -> endTable()
            "tr" -> endRow()
            "td", "th" -> endCell()
        }
    }

    private fun text(raw: String) {
        if (ignoreDepth > 0 || raw.isEmpty()) return
        val currentCell = cell
        val target = currentCell?.builder ?: builder
        if (preDepth > 0) {
            val kept = raw.replace("\r\n", "\n").replace('\r', '\n')
            target.append(kept, format)
            lastSpace = false
            currentCell?.atLineStart = kept.endsWith('\n')
            return
        }
        val run = StringBuilder(raw.length)
        for (c in raw) {
            if (isHtmlSpace(c)) {
                if (!lastSpace) {
                    run.append(' ')
                    lastSpace = true
                }
            } else {
                run.append(c)
                lastSpace = false
            }
        }
        if (run.isEmpty()) return
        target.append(run.toString(), format)
        if (!(run.length == 1 && run[0] == ' ')) currentCell?.atLineStart = false
    }

    private fun lineBreak() {
        val currentCell = cell
        (currentCell?.builder ?: builder).append("\n", format)
        currentCell?.atLineStart = true
        lastSpace = true
    }

    private fun pushFormat(frame: Frame, next: CharFormat) {
        frame.restore = format
        format = next
    }

    private fun inlineFormat(tag: String, p: XmlPullParser, dir: String): CharFormat? = when (tag) {
        "b", "strong" -> format.copy(bold = true)
        "i", "em", "cite", "dfn", "var" -> format.copy(italic = true)
        "u", "ins" -> format.copy(underline = true)
        "s", "strike", "del" -> format.copy(strike = true)
        "sup" -> format.copy(script = ScriptShift.SUPER)
        "sub" -> format.copy(script = ScriptShift.SUB)
        "code", "kbd", "samp", "tt" -> format.copy(mono = true)
        "mark" -> format.copy(background = MARK_BACKGROUND)
        "a" -> linkFormat(p.getAttributeValue(null, "href"), dir)
        else -> null
    }

    /**
     * A link opens the app's own handlers, so it is one of two things only: a web, mail or phone address that
     * [safeLink] lets through, or a place in this book. The two are never both set; anything else is plain text.
     */
    private fun linkFormat(href: String?, dir: String): CharFormat? {
        safeLink(href)?.let { return format.copy(link = it, bookLink = null) }
        return bookLinkOf(dir, href, chapter)?.let { format.copy(link = null, bookLink = it) }
    }

    /** What to cut from the ends of a paragraph: all whitespace, except in `pre`, where only line breaks go. */
    private fun trimmable(): (Char) -> Boolean {
        val keepSpaces = preDepth > 0
        return { c: Char -> if (keepSpaces) c == '\n' || c == '\r' else isHtmlSpace(c) }
    }

    /**
     * Ends the paragraph being built and emits it as what the surrounding markup says it is: a heading, a
     * list item (the first one of an `li` carries its marker) or a paragraph indented by its block quotes.
     * Inside a table cell, blocks are not blocks but lines of the cell.
     */
    private fun flushBlock() {
        if (cell != null) {
            cellBreak()
            return
        }
        val snapshot = builder.snapshot(trimmable())
        builder.clear()
        lastSpace = true
        if (snapshot.text.isBlank()) return
        val item = items.lastOrNull()
        val block = when {
            headingLevel > 0 -> OfficeBlock.Heading(snapshot.text, headingLevel, snapshot.spans, direction = direction)
            item != null -> {
                val marker = item.marker.orEmpty()
                item.marker = null
                OfficeBlock.ListItem(snapshot.text, marker, item.level, snapshot.spans, direction = direction)
            }
            else -> OfficeBlock.Paragraph(
                snapshot.text,
                snapshot.spans,
                indent = quoteDepth.coerceAtMost(MAX_QUOTE_INDENT),
                direction = direction,
            )
        }
        sink.add(block)
    }

    private fun cellBreak() {
        val current = cell ?: return
        if (!current.atLineStart) {
            current.builder.append("\n")
            current.atLineStart = true
        }
        lastSpace = true
    }

    private fun startItem(p: XmlPullParser) {
        flushBlock()
        val list = lists.lastOrNull()
        val level = (lists.size - 1).coerceIn(0, MAX_LIST_LEVEL)
        val marker = if (list != null && list.ordered) {
            p.getAttributeValue(null, "value")?.trim()?.toIntOrNull()?.let { list.next = it }
            val text = formatListNumber(list.next, list.numberFormat) + "."
            list.next++
            text
        } else {
            displayBullet(null, level)
        }
        val currentCell = cell
        if (currentCell != null) {
            // A cell has no room for a margin: the marker goes in the text, on its own line.
            items += ItemFrame(level, null)
            currentCell.builder.append("$marker ")
            currentCell.atLineStart = false
        } else {
            items += ItemFrame(level, marker)
        }
    }

    private fun image(src: String?, alt: String?, width: String?, dir: String) {
        val path = src?.let { resolveEpubPath(dir, it) } ?: return
        // A cell holds text only, and the image's place in a paragraph is between its two halves.
        if (cell != null) return
        flushBlock()
        if (sink.full) {
            sink.truncated = true
            return
        }
        val widthDp = width?.trim()?.removeSuffix("px")?.toFloatOrNull()?.takeIf { it > 0f }?.coerceAtMost(MAX_IMAGE_WIDTH_DP)
        val description = alt?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_ALT_CHARS)
        sink.pending += PendingImage(path, sink.blocks.size, widthDp, description)
    }

    private fun startTable() {
        tableDepth++
        if (tableDepth == 1) {
            flushBlock()
            table = TableState()
        } else {
            separateNested()
        }
    }

    private fun startRow() {
        val state = table ?: return
        if (tableDepth > 1) {
            separateNested()
            return
        }
        if (state.row != null) endRow()
        state.row = ArrayList()
        state.column = 0
    }

    private fun startCell(p: XmlPullParser, header: Boolean, frame: Frame) {
        val state = table ?: return
        if (tableDepth > 1) {
            separateNested()
            return
        }
        if (state.row == null) startRow()
        var column = state.column
        while ((state.covered[column] ?: 0) > 0) column++
        val colspan = p.getAttributeValue(null, "colspan")?.trim()?.toIntOrNull()?.coerceIn(1, MAX_SPAN) ?: 1
        val rowspan = p.getAttributeValue(null, "rowspan")?.trim()?.toIntOrNull()?.coerceIn(1, MAX_SPAN) ?: 1
        cell = CellState(column, colspan, rowspan)
        lastSpace = true
        if (header) pushFormat(frame, format.copy(bold = true))
    }

    private fun endCell() {
        val state = table ?: return
        if (tableDepth > 1) return
        val finished = cell ?: return
        val snapshot = finished.builder.snapshot { isHtmlSpace(it) }
        state.row?.add(OfficeCell(snapshot.text, finished.column, finished.colspan, finished.rowspan, snapshot.spans))
        for (k in finished.column until finished.column + finished.colspan) state.covered[k] = finished.rowspan
        state.column = finished.column + finished.colspan
        cell = null
        lastSpace = true
    }

    private fun endRow() {
        val state = table ?: return
        if (tableDepth > 1) return
        if (cell != null) endCell()
        val row = state.row
        state.row = null
        if (row != null && row.isNotEmpty()) {
            if (state.rows.size >= sink.limits.maxTableRows) sink.truncated = true else state.rows += row
        }
        // This row is over: every cell that reaches into the next ones has one row fewer to go.
        val it = state.covered.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            entry.setValue(entry.value - 1)
            if (entry.value <= 0) it.remove()
        }
    }

    private fun endTable() {
        tableDepth--
        if (tableDepth > 0) {
            separateNested()
            return
        }
        if (cell != null) endCell()
        if (table?.row != null) endRow()
        val rows = table?.rows.orEmpty()
        table = null
        if (rows.isNotEmpty()) sink.add(OfficeBlock.Table(rows))
    }

    /** A table inside a cell is not drawn as one: its pieces become words of the cell, set apart by a space. */
    private fun separateNested() {
        val current = cell ?: return
        if (!lastSpace) {
            current.builder.append(" ")
            lastSpace = true
        }
    }

    /** The value of the attribute called [name] under any namespace prefix (`href`, `xlink:href`, `xl:href`). */
    private fun attribute(p: XmlPullParser, name: String): String? {
        for (i in 0 until p.attributeCount) {
            if (p.getAttributeName(i).substringAfter(':') == name) return p.getAttributeValue(i)
        }
        return null
    }

    /** `hidden`, or a `display:none` style: the author asked for it not to be shown. */
    private fun isHidden(p: XmlPullParser): Boolean {
        if (p.getAttributeValue(null, "hidden") != null) return true
        val style = p.getAttributeValue(null, "style") ?: return false
        return style.replace(" ", "").lowercase().contains("display:none")
    }
}
