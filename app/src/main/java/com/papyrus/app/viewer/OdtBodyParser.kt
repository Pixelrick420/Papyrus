package com.papyrus.app.viewer

import org.xmlpull.v1.XmlPullParser

/** Subtrees whose text is annotation rather than body: comments (author and timestamp included), deleted text. */
private val ODT_SKIPPED = setOf("office:annotation", "text:tracked-changes")

private const val MAX_LIST_LEVEL = 8

private fun XmlPullParser.attr(name: String): String? = getAttributeValue(null, name)

/** A span or repeat count: at least 1, and no more than [MAX_MERGE_SPAN] so a hostile file cannot ask for thousands. */
private fun XmlPullParser.countAttr(name: String): Int =
    getAttributeValue(null, name)?.toIntOrNull()?.coerceIn(1, MAX_MERGE_SPAN) ?: 1

/** ODT heading depth comes from an automatic style, so the attribute sits on the element here. */
private fun XmlPullParser.outlineLevel(): Int =
    getAttributeValue(null, "text:outline-level")?.toIntOrNull()?.coerceIn(1, 6) ?: 1

/**
 * Reads an ODF `content.xml` body into [blocks], a single pass as a state machine as [DocxBodyParser]
 * notes. Not safe to reuse: one instance parses one document.
 */
internal class OdtBodyParser(
    private val newParser: ParserFactory,
    private val styles: OdfStyles,
    private val pending: MutableList<PendingImage>,
) {
    val blocks = mutableListOf<OfficeBlock>()
    private val footnotes = ArrayList<OfficeBlock.Note>()
    private val endnotes = ArrayList<OfficeBlock.Note>()

    /** What the paragraph being read is: its style, whether it is a heading, and where it sits in a list. */
    private class ParaInfo(val styleName: String?, val headingLevel: Int, val listMarker: String?, val listLevel: Int)

    /** One open `text:list`. [counter] is the number of the current item. */
    private class ListFrame(val styleName: String?, var counter: Int) {
        var markerPending = false
        var header = false
    }

    /** The anchoring paragraph's state while a text box's own paragraphs are parsed. */
    private class SetAsideParagraph(
        val snapshot: RichSnapshot,
        val depth: Int,
        val info: ParaInfo,
        val elementDepth: Int,
    )

    /**
     * A `text:note` being read. Its body is kept apart from the paragraph it annotates, and the
     * formatting state of that paragraph is put away until the note is closed.
     */
    private class NoteFrame(val endnote: Boolean, val savedSpans: List<RunProps>, val savedLink: String?) {
        val body = RichTextBuilder()
        val citation = StringBuilder()
        var inCitation = false
        var paragraphDepth = 0
        var styleName: String? = null
    }

    private val noInfo = ParaInfo(null, 0, null, 0)
    private val paragraph = RichTextBuilder()
    private val cell = RichTextBuilder()
    private var paragraphDepth = 0
    private var info = noInfo
    private var tableDepth = 0
    private val grid = Grid()
    private var inCell = false

    // ODT marks a vertical merge with a sibling `table:covered-table-cell` rather than an
    // attribute, so there is no merge state to track here, only a column cursor to advance.
    private var column = 0
    private var tableUsable = true

    // How many <text:p>/<text:h> are open: only text inside one is content. A depth, not a flag, so a
    // nested frame's inner end tag does not switch off the outer paragraph's remaining text.
    private var paragraphElementDepth = 0

    // Comments and deleted text sit *inside* the paragraph they annotate. Their text is not part of it.
    private var skipDepth = 0
    private val textBoxes = ArrayDeque<SetAsideParagraph>()

    // Merge geometry. Real files declare spans as attributes, then add covered cells as placeholders.
    private var cellColspan = 1
    private var cellRowspan = 1
    private var cellRepeat = 1
    private var rowIndex = -1
    private var tableColumns = 0
    private var horizontalCoversLeft = 0

    // column -> last row index that a rowspan from above still covers
    private val verticalCoveredUntil = HashMap<Int, Int>()
    private var cellFill: Int? = null
    private var cellAlign: BlockAlign? = null
    private var cellParagraphStart = 0

    private val listStack = ArrayList<ListFrame>()

    // depth -> where the list that last closed at that depth had got to, for `text:continue-numbering`.
    private val lastCounter = HashMap<Int, Int>()

    // How many lists were already open when the table began: a list deeper than this is *in* the cell.
    private var listDepthAtTable = 0

    private val spanStack = ArrayList<RunProps>()
    private var link: String? = null
    private var format = CharFormat.PLAIN
    private val interned = HashMap<CharFormat, CharFormat>()

    private var note: NoteFrame? = null
    private var footnoteCount = 0
    private var endnoteCount = 0

    private var frameWidthDp: Float? = null
    private var lastImage: PendingImage? = null
    private var altText: StringBuilder? = null

    fun parse(body: ByteArray): MutableList<OfficeBlock> {
        val p = newParser(body.inputStream())
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT && blocks.size < OfficeTextExtractor.MAX_BLOCKS) {
            val skippedTag = (event == XmlPullParser.START_TAG || event == XmlPullParser.END_TAG) &&
                p.name in ODT_SKIPPED
            if (skippedTag) {
                if (event == XmlPullParser.START_TAG) skipDepth++ else if (skipDepth > 0) skipDepth--
            } else if (skipDepth == 0) when (event) {
                XmlPullParser.START_TAG -> onStart(p)
                XmlPullParser.TEXT -> onText(p.text)
                XmlPullParser.END_TAG -> onEnd(p)
            }
            event = p.next()
        }
        appendNotes()
        return blocks
    }

    /** What text formatted by the paragraph's style and the open spans looks like. */
    private fun recomputeFormat() {
        val open = note
        val style = if (open != null) open.styleName else info.styleName
        var props = styles.paragraphRun(style)
        for (span in spanStack) props = span.mergedOver(props)
        val raw = props.toFormat(link)
        format = interned.getOrPut(raw) { raw }
    }

    /**
     * Text goes to a note's body in a note, a table cell's buffer in a cell, else the paragraph, and
     * only from inside a paragraph element: whitespace between tags is layout, not content.
     */
    private fun appendText(text: String, withFormat: CharFormat = format) {
        val open = note
        if (open != null) {
            if (open.paragraphDepth > 0) open.body.append(text, withFormat)
            return
        }
        if (paragraphElementDepth == 0) return
        (if (inCell) cell else paragraph).append(text, withFormat)
    }

    private fun onText(text: String) {
        val open = note
        if (open != null && open.inCitation) {
            open.citation.append(text)
            return
        }
        val alt = altText
        if (alt != null) {
            alt.append(text)
            return
        }
        appendText(text)
    }

    private fun onStart(p: XmlPullParser) {
        when (p.name) {
            "table:table" -> {
                if (tableDepth == 0) {
                    emitParagraph()
                    grid.reset()
                    tableUsable = true
                    rowIndex = -1
                    tableColumns = 0
                    verticalCoveredUntil.clear()
                    listDepthAtTable = listStack.size
                }
                tableDepth++
            }
            "table:table-column" -> if (tableDepth == 1) {
                tableColumns += p.countAttr("table:number-columns-repeated")
            }
            "table:table-row" -> if (tableDepth == 1) {
                // Depth 1 only, as in DOCX: a nested row must not reset the outer grid's in-progress row.
                grid.startRow()
                column = 0
                rowIndex++
                horizontalCoversLeft = 0
            }
            "table:table-cell" -> if (tableDepth == 1) {
                inCell = true
                cell.clear()
                cellColspan = p.countAttr("table:number-columns-spanned")
                cellRowspan = p.countAttr("table:number-rows-spanned")
                cellRepeat = p.countAttr("table:number-columns-repeated")
                cellFill = styles.cellBackground(p.attr("table:style-name"))
                cellAlign = null
            }
            "table:covered-table-cell" -> if (tableDepth == 1) {
                repeat(p.countAttr("table:number-columns-repeated")) {
                    when {
                        // Right of a cell that declared `number-columns-spanned`: the cell already carries
                        // the span and moved the column cursor past it, so there is nothing left to do.
                        horizontalCoversLeft > 0 -> horizontalCoversLeft--
                        // Under a rowspan from above: absent, as in DOCX, but it does occupy its column;
                        // widening a neighbour here stretched the cell beside a middle-column merge.
                        (verticalCoveredUntil[column] ?: -1) >= rowIndex -> column++
                        // A producer that omits the spanned attribute: a covered cell extends its left neighbour.
                        else -> {
                            grid.widenLastColumn()
                            column++
                        }
                    }
                }
            }
            "draw:text-box" -> if (tableDepth == 0) {
                textBoxes.addLast(SetAsideParagraph(paragraph.snapshot(), paragraphDepth, info, paragraphElementDepth))
                paragraph.clear()
                paragraphDepth = 0
                info = noInfo
                paragraphElementDepth = 0
            }
            "text:p", "text:h" -> startParagraph(p)
            "text:span" -> {
                spanStack += styles.textRun(p.attr("text:style-name"))
                recomputeFormat()
            }
            "text:a" -> {
                link = safeLink(p.attr("xlink:href"))
                recomputeFormat()
            }
            "text:list" -> startList(p)
            "text:list-item" -> startListItem(p)
            "text:list-header" -> listStack.lastOrNull()?.let {
                it.header = true
                it.markerPending = false
            }
            "text:note" -> startNote(p)
            "text:note-citation" -> note?.inCitation = true
            "draw:frame" -> frameWidthDp = lengthToTwips(p.attr("svg:width"))?.let { twipsToDp(it) }
            "draw:image" -> if (tableDepth == 0) {
                p.attr("xlink:href")?.let { href ->
                    val image = PendingImage(href, blocks.size, frameWidthDp, null)
                    pending += image
                    lastImage = image
                }
            }
            "svg:title", "svg:desc" -> altText = StringBuilder()
            "text:tab" -> appendText("\t")
            "text:line-break" -> appendText("\n")
            "text:s" -> repeat(p.attr("text:c")?.toIntOrNull() ?: 1) { appendText(" ") }
        }
    }

    private fun onEnd(p: XmlPullParser) {
        when (p.name) {
            "table:table-row" -> if (tableDepth == 1) {
                tableUsable = grid.commitRow(rowLimit = OfficeTextExtractor.MAX_TABLE_ROWS)
            }
            "table:table-cell" -> if (inCell && tableDepth == 1) endCell()
            "draw:text-box" -> if (tableDepth == 0 && textBoxes.isNotEmpty()) {
                emitParagraph() // text in the box that no `</text:p>` closed
                val host = textBoxes.removeLast()
                paragraph.appendRich(host.snapshot.text, host.snapshot.spans)
                paragraphDepth = host.depth
                info = host.info
                paragraphElementDepth = host.elementDepth
                recomputeFormat()
            }
            "table:table" -> {
                tableDepth--
                if (tableDepth == 0) {
                    if (grid.rows.isNotEmpty() && tableUsable) blocks += grid.emit()
                    grid.reset()
                }
            }
            "text:p", "text:h" -> endParagraph()
            "text:span" -> {
                if (spanStack.isNotEmpty()) spanStack.removeAt(spanStack.lastIndex)
                recomputeFormat()
            }
            "text:a" -> {
                link = null
                recomputeFormat()
            }
            "text:list" -> endList()
            "text:note-citation" -> note?.inCitation = false
            "text:note" -> endNote()
            "draw:frame" -> {
                frameWidthDp = null
                lastImage = null
            }
            "svg:title", "svg:desc" -> {
                val text = altText?.toString()?.trim().orEmpty()
                altText = null
                val image = lastImage
                // A description is the better alt text; a title only fills in when there is none.
                if (image != null && text.isNotEmpty() && (p.name == "svg:desc" || image.altText == null)) {
                    image.altText = text
                }
            }
        }
    }

    private fun endCell() {
        inCell = false
        val snapshot = cell.snapshot { it == '\n' }
        val content = CellContent(snapshot.text, snapshot.spans, cellFill, cellAlign ?: BlockAlign.START)
        // A repeat cannot run past the declared columns, or a spreadsheet-style
        // "repeat 1000 empty cells" would add a thousand columns.
        val room = if (tableColumns > 0) (tableColumns - column).coerceAtLeast(1) else cellRepeat
        repeat(minOf(cellRepeat, room)) {
            grid.openCellAt(column, content, cellColspan, MergeState.NONE).rowspan = cellRowspan
            if (cellRowspan > 1) {
                for (c in column until column + cellColspan) {
                    verticalCoveredUntil[c] = rowIndex + cellRowspan - 1
                }
            }
            column += cellColspan
        }
        horizontalCoversLeft = cellColspan - 1
        cell.clear()
    }

    private fun startParagraph(p: XmlPullParser) {
        val open = note
        if (open != null) {
            if (open.paragraphDepth++ == 0) {
                if (open.body.length > 0) open.body.append("\n")
                open.styleName = p.attr("text:style-name")
                recomputeFormat()
            }
            return
        }
        val outermost = paragraphElementDepth == 0
        paragraphElementDepth++
        if (tableDepth == 0 && paragraphDepth++ == 0) paragraph.clear()
        if (outermost) {
            info = buildInfo(p)
            recomputeFormat()
            if (tableDepth == 1) cellParagraphStart = cell.length
        }
    }

    private fun endParagraph() {
        val open = note
        if (open != null) {
            if (open.paragraphDepth > 0) open.paragraphDepth--
            return
        }
        if (paragraphElementDepth > 0) paragraphElementDepth--
        when {
            // One newline between a cell's paragraphs, as in DOCX: without it a cell holding two
            // paragraphs, or an outer cell holding a nested table's cell, reads as one run-on line.
            tableDepth == 0 -> if (--paragraphDepth == 0) emitParagraph()
            inCell -> endCellParagraph()
        }
    }

    private fun buildInfo(p: XmlPullParser): ParaInfo {
        val styleName = p.attr("text:style-name")
        val heading = if (p.name == "text:h") p.outlineLevel() else styles.headingLevel(styleName)
        var marker: String? = null
        var level = 0
        if (listStack.isNotEmpty() && (tableDepth == 0 || listStack.size > listDepthAtTable)) {
            marker = consumeMarker()
            level = listStack.size - 1
        }
        return ParaInfo(styleName, heading, marker, level)
    }

    private fun emitParagraph() {
        if (!paragraph.isBlank() && blocks.size < OfficeTextExtractor.MAX_BLOCKS) {
            val snapshot = paragraph.snapshot()
            val align = styles.paragraphAlign(info.styleName) ?: BlockAlign.START
            val marker = info.listMarker
            blocks += when {
                info.headingLevel > 0 -> OfficeBlock.Heading(snapshot.text, info.headingLevel, snapshot.spans, align)
                marker != null -> OfficeBlock.ListItem(
                    snapshot.text,
                    marker,
                    info.listLevel.coerceIn(0, MAX_LIST_LEVEL),
                    snapshot.spans,
                    align,
                )
                else -> OfficeBlock.Paragraph(
                    snapshot.text,
                    snapshot.spans,
                    align,
                    indentLevel(styles.paragraphIndentTwips(info.styleName)),
                )
            }
        }
        paragraph.clear()
        info = noInfo
    }

    /**
     * A cell draws as lines of text, so a list item there carries its marker in the text. Alignment is
     * the first paragraph's: a column of figures is right-aligned paragraph by paragraph.
     */
    private fun endCellParagraph() {
        if (tableDepth == 1 && paragraphElementDepth == 0) {
            if (cellAlign == null) cellAlign = styles.paragraphAlign(info.styleName) ?: BlockAlign.START
            val marker = info.listMarker
            if (!marker.isNullOrEmpty() && cell.length > cellParagraphStart) {
                cell.insert(cellParagraphStart, "$marker ")
            }
        }
        cell.append("\n")
    }

    private fun startList(p: XmlPullParser) {
        // A nested list that names no style continues its parent's.
        val styleName = p.attr("text:style-name") ?: listStack.lastOrNull()?.styleName
        val depth = listStack.size + 1
        val levelStart = (styles.listLevels(styleName)?.get(depth)?.start ?: 1) - 1
        val continued = p.attr("text:continue-numbering") == "true"
        val counter = if (continued) lastCounter[depth] ?: levelStart else levelStart
        listStack += ListFrame(styleName, counter)
    }

    private fun endList() {
        if (listStack.isEmpty()) return
        val frame = listStack.removeAt(listStack.lastIndex)
        lastCounter[listStack.size + 1] = frame.counter
    }

    private fun startListItem(p: XmlPullParser) {
        val frame = listStack.lastOrNull() ?: return
        frame.header = false
        val startValue = p.attr("text:start-value")?.toIntOrNull()
        if (startValue != null) frame.counter = startValue else frame.counter++
        frame.markerPending = true
    }

    /**
     * The marker for the paragraph that starts here. Only an item's *first* paragraph carries one; the
     * rest are continuations, indented with it but unmarked, and a list header is never numbered.
     */
    private fun consumeMarker(): String {
        val frame = listStack.last()
        if (frame.header || !frame.markerPending) return ""
        frame.markerPending = false
        return markerAt(listStack.size)
    }

    private fun markerAt(depth: Int): String {
        val frame = listStack[depth - 1]
        val level = styles.listLevels(frame.styleName)?.get(depth)
        if (level == null || level.bullet) return displayBullet(level?.bulletChar, depth - 1)
        // "1.1." shows the counters of the last `display-levels` levels joined by dots.
        val shown = level.displayLevels.coerceIn(1, depth)
        val parts = ArrayList<String>()
        for (d in depth - shown + 1..depth) {
            val owner = listStack[d - 1]
            val format = styles.listLevels(owner.styleName)?.get(d)?.format ?: "decimal"
            parts += formatListNumber(owner.counter, format)
        }
        return level.prefix + parts.joinToString(".") + level.suffix
    }

    private fun startNote(p: XmlPullParser) {
        note = NoteFrame(p.attr("text:note-class") == "endnote", ArrayList(spanStack), link)
        spanStack.clear()
        link = null
    }

    /**
     * The citation is the label the document chose, falling back to a count; the marker goes into the
     * paragraph that held the note, which is where the note began since its body added nothing to it.
     */
    private fun endNote() {
        val frame = note ?: return
        note = null
        spanStack.clear()
        spanStack.addAll(frame.savedSpans)
        link = frame.savedLink
        recomputeFormat()
        val label = frame.citation.toString().trim().ifEmpty {
            if (frame.endnote) formatListNumber(++endnoteCount, "lowerRoman") else (++footnoteCount).toString()
        }
        appendText(label, format.copy(script = ScriptShift.SUPER))
        val snapshot = frame.body.snapshot { it.isWhitespace() }
        if (snapshot.text.isNotBlank() && blocks.size + footnotes.size + endnotes.size < OfficeTextExtractor.MAX_BLOCKS) {
            val item = OfficeBlock.Note(label, snapshot.text, snapshot.spans)
            if (frame.endnote) endnotes += item else footnotes += item
        }
    }

    private fun appendNotes() {
        if (footnotes.isEmpty() && endnotes.isEmpty()) return
        if (blocks.size >= OfficeTextExtractor.MAX_BLOCKS) return
        blocks += OfficeBlock.Divider
        blocks.addAll(footnotes)
        blocks.addAll(endnotes)
    }
}
