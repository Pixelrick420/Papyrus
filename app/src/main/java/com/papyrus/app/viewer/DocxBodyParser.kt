package com.papyrus.app.viewer

import org.xmlpull.v1.XmlPullParser

/**
 * [targets]: `rId7 -> word/media/image3.png`, relative targets resolved. [links]: `rId9 -> the URL`,
 * for relationships marked external. Kept apart because an external target is not an archive entry.
 */
internal class DocxRels(val targets: Map<String, String>, val links: Map<String, String>) {
    companion object {
        val EMPTY = DocxRels(emptyMap(), emptyMap())
    }
}

/** A footnote or endnote the body pointed at, in reading order; [label] is the marker left in the text. */
internal class NoteRef(val endnote: Boolean, val id: String, val label: String)

private val HYPERLINK_FIELD = Regex("HYPERLINK\\s+\"([^\"]+)\"", RegexOption.IGNORE_CASE)

/** 914,400 EMU to the inch, 160 dp to the inch. */
private const val EMU_PER_DP = 5715.0

private const val MAX_LIST_LEVEL = 8

private fun XmlPullParser.attr(name: String): String? = getAttributeValue(null, name)

private fun XmlPullParser.intAttr(name: String): Int? = getAttributeValue(null, name)?.toIntOrNull()

/**
 * Reads one WordprocessingML part (`document.xml`, or `footnotes.xml` / `endnotes.xml`) into
 * [blocks], walking the XML once as a state machine. Not safe to reuse: one instance, one part.
 */
internal class DocxBodyParser(
    private val newParser: ParserFactory,
    private val styles: DocxStyles,
    private val counters: ListCounters,
    private val rels: DocxRels,
    private val pending: MutableList<PendingImage>,
) {
    val blocks = mutableListOf<OfficeBlock>()

    /** Footnotes and endnotes the *body* referenced. Empty for a notes part. */
    val noteRefs = mutableListOf<NoteRef>()

    /** For a notes part: id -> the note's text. */
    val notes = HashMap<String, RichSnapshot>()

    /** What `w:pPr` said about the paragraph being read. */
    private class ParaState {
        var styleId: String? = null
        var align: BlockAlign? = null
        var indentTwips: Int? = null
        var numId: Int? = null
        var numLevel: Int? = null
        var outline: Int? = null
    }

    /** The anchoring paragraph's state while a text box's own paragraphs are parsed. */
    private class SetAsideParagraph(val snapshot: RichSnapshot, val depth: Int, val para: ParaState)

    private val paragraph = RichTextBuilder()
    private val cell = RichTextBuilder()
    private var para = ParaState()
    private var paragraphDepth = 0
    private var inText = false
    private var tableDepth = 0
    private val grid = Grid()
    private var column = 0
    private var inCell = false
    private var cellColspan = 1
    private var cellMerge = MergeState.NONE
    private var cellFill: Int? = null
    private var cellAlign: BlockAlign? = null
    private var cellParagraphStart = 0
    private var tableUsable = true

    // `w:tab` is also a tab-stop *definition* inside `w:tabs`; only a run's `w:tab` is a character.
    private var inTabStops = false

    // Inside `mc:Fallback`: the legacy copy of what `mc:Choice` already said.
    private var fallbackDepth = 0

    // A text box's paragraphs nest inside the paragraph that anchors it; they are set aside so the
    // box reads as its own paragraphs instead of being glued onto the anchor's text.
    private val textBoxes = ArrayDeque<SetAsideParagraph>()

    private var runProps = RunProps()
    private var runStyleId: String? = null
    private var runFormat: CharFormat? = null
    private val interned = HashMap<CharFormat, CharFormat>()
    private var inRPr = false
    private var inPPr = false
    private var inTcPr = false

    // Inside a `...PrChange`: the *previous* properties of a tracked change, not the current ones.
    private var changeDepth = 0

    private var link: String? = null
    private var fieldLink: String? = null
    private var inInstrText = false
    private val instruction = StringBuilder()

    private var footnoteCount = 0
    private var endnoteCount = 0
    private var noteId: String? = null
    private var noteIsSeparator = false
    private var noteStart = 0

    private var imageWidthEmu: Long? = null
    private var imageAlt: String? = null

    fun parse(body: ByteArray) {
        val p = newParser(body.inputStream())
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT && blocks.size < OfficeTextExtractor.MAX_BLOCKS) {
            val isFallbackTag = (event == XmlPullParser.START_TAG || event == XmlPullParser.END_TAG) &&
                p.name == "mc:Fallback"
            if (isFallbackTag) {
                if (event == XmlPullParser.START_TAG) fallbackDepth++ else if (fallbackDepth > 0) fallbackDepth--
            } else if (fallbackDepth == 0) when (event) {
                XmlPullParser.START_TAG -> onStart(p)
                XmlPullParser.TEXT -> onText(p.text)
                XmlPullParser.END_TAG -> onEnd(p)
            }
            event = p.next()
        }
    }

    /** The format of the run being read: styles first, then the run's own properties, then any link. */
    private fun format(): CharFormat {
        runFormat?.let { return it }
        val base = styles.runProps(para.styleId, runStyleId)
        val raw = runProps.mergedOver(base).toFormat(link)
        val shared = interned.getOrPut(raw) { raw }
        runFormat = shared
        return shared
    }

    /**
     * Parser text goes to the cell buffer inside a `w:tc`, else the paragraph, and only inside a
     * `w:t`; [element] marks characters an element contributes (a tab, a break) regardless of that.
     */
    private fun appendText(raw: String, element: Boolean = false, withFormat: CharFormat? = null) {
        if (inText || element) {
            (if (inCell) cell else paragraph).append(raw, withFormat ?: format())
        }
    }

    private fun onText(text: String) {
        if (inInstrText) {
            instruction.append(text)
            return
        }
        appendText(text)
    }

    private fun onStart(p: XmlPullParser) {
        val name = p.name
        if (name.endsWith("PrChange")) {
            changeDepth++
            return
        }
        if (changeDepth > 0) return
        if (inRPr && name != "w:rPr") {
            if (name == "w:rStyle") runStyleId = p.attr("w:val") else runProps.applyDocx(p)
            return
        }
        when (name) {
            "w:tbl" -> {
                if (tableDepth == 0) {
                    emitParagraph() // a table interrupts a paragraph, it never nests in one
                    grid.reset()
                    tableUsable = true
                }
                tableDepth++
            }
            "w:tr" -> if (tableDepth == 1) {
                // Depth 1 only: a nested table's startRow() would discard the outer row being assembled.
                grid.startRow()
                column = 0
            }
            "w:tc" -> if (tableDepth == 1) {
                // A nested cell (depth > 1) sits inside an outer cell that is still open, so `inCell` stays
                // true and its text lands in the outer cell's buffer, which is therefore not reset.
                inCell = true
                cell.clear()
                cellColspan = 1
                cellMerge = MergeState.NONE
                cellFill = null
                cellAlign = null
            }
            "w:tcPr" -> inTcPr = true
            "w:shd" -> if (inTcPr && inCell && tableDepth == 1) {
                val fill = p.attr("w:fill")
                cellFill = if (fill == null || fill == "auto") null else parseHexColor(fill)
            }
            "w:gridSpan" -> if (inCell && tableDepth == 1) {
                cellColspan = p.intAttr("w:val")?.coerceIn(1, MAX_MERGE_SPAN) ?: 1
            }
            "w:vMerge" -> if (inCell && tableDepth == 1) {
                // val="restart" opens a merge, "continue" extends one, and no val at all means continue.
                cellMerge = when (p.attr("w:val")) {
                    "restart" -> MergeState.RESTART
                    null, "continue" -> MergeState.CONTINUE
                    else -> MergeState.NONE
                }
            }
            "w:p" -> {
                para = ParaState()
                if (tableDepth == 0 && paragraphDepth++ == 0) paragraph.clear()
                if (tableDepth == 1) cellParagraphStart = cell.length
            }
            "w:pPr" -> inPPr = true
            "w:pStyle" -> if (inPPr) para.styleId = p.attr("w:val")
            "w:jc" -> if (inPPr) para.align = alignOf(p.attr("w:val"))
            "w:ind" -> if (inPPr) para.indentTwips = (p.attr("w:left") ?: p.attr("w:start"))?.toIntOrNull()
            "w:outlineLvl" -> if (inPPr) para.outline = p.intAttr("w:val")
            "w:numId" -> if (inPPr) para.numId = p.intAttr("w:val")
            "w:ilvl" -> if (inPPr) para.numLevel = p.intAttr("w:val")
            "w:r" -> {
                runProps = RunProps()
                runStyleId = null
                runFormat = null
            }
            // A paragraph mark's own run properties sit inside w:pPr and describe the mark, not the text.
            "w:rPr" -> if (!inPPr) inRPr = true
            "w:hyperlink" -> link = safeLink(p.attr("r:id")?.let { rels.links[it] })
            "w:fldSimple" -> startField(p.attr("w:instr").orEmpty())
            "w:fldChar" -> when (p.attr("w:fldCharType")) {
                "begin" -> instruction.setLength(0)
                "separate" -> startField(instruction.toString())
                "end" -> endField()
            }
            "w:instrText" -> inInstrText = true
            "w:footnoteReference", "w:endnoteReference" -> addNoteReference(p, endnote = name == "w:endnoteReference")
            "w:footnote", "w:endnote" -> {
                noteId = p.attr("w:id")
                val type = p.attr("w:type")
                noteIsSeparator = type == "separator" || type == "continuationSeparator" || type == "continuationNotice"
                noteStart = blocks.size
            }
            "wp:inline", "wp:anchor" -> {
                imageWidthEmu = null
                imageAlt = null
            }
            "wp:extent" -> imageWidthEmu = p.attr("cx")?.toLongOrNull()
            "wp:docPr" -> imageAlt = (p.attr("descr") ?: p.attr("title"))?.takeIf { it.isNotBlank() }
            // a:blip is DrawingML; v:imagedata is the legacy VML fallback Word still emits.
            "a:blip", "v:imagedata" -> if (tableDepth == 0) {
                val id = p.attr("r:embed") ?: p.attr("r:id")
                val target = id?.let { rels.targets[it] }
                if (target != null) pending += PendingImage(target, blocks.size, imageWidthDp(), imageAlt)
                imageWidthEmu = null
                imageAlt = null
            }
            "w:t" -> inText = true
            "w:tabs" -> inTabStops = true
            "w:tab" -> if (!inTabStops) appendText("\t", element = true)
            "w:ptab" -> appendText("\t", element = true)
            "w:noBreakHyphen" -> appendText("-", element = true)
            "w:br", "w:cr" -> appendText("\n", element = true)
            "w:txbxContent" -> if (tableDepth == 0) {
                textBoxes.addLast(SetAsideParagraph(paragraph.snapshot(), paragraphDepth, para))
                paragraph.clear()
                paragraphDepth = 0
                para = ParaState()
            }
        }
    }

    private fun onEnd(p: XmlPullParser) {
        val name = p.name
        if (name.endsWith("PrChange")) {
            if (changeDepth > 0) changeDepth--
            return
        }
        if (changeDepth > 0) return
        when (name) {
            "w:t" -> inText = false
            "w:tabs" -> inTabStops = false
            "w:rPr" -> inRPr = false
            "w:pPr" -> inPPr = false
            "w:tcPr" -> inTcPr = false
            "w:instrText" -> inInstrText = false
            "w:hyperlink" -> link = fieldLink
            "w:fldSimple" -> endField()
            "w:txbxContent" -> if (tableDepth == 0 && textBoxes.isNotEmpty()) {
                emitParagraph() // text in the box that no `</w:p>` closed
                val host = textBoxes.removeLast()
                paragraph.appendRich(host.snapshot.text, host.snapshot.spans)
                paragraphDepth = host.depth
                para = host.para
            }
            "w:tc" -> if (inCell && tableDepth == 1) {
                inCell = false
                val snapshot = cell.snapshot { it == '\n' }
                val content = CellContent(snapshot.text, snapshot.spans, cellFill, cellAlign ?: BlockAlign.START)
                grid.openCellAt(column, content, cellColspan, cellMerge)
                column += cellColspan
                cell.clear()
            }
            "w:tr" -> if (tableDepth == 1) {
                tableUsable = grid.commitRow(rowLimit = OfficeTextExtractor.MAX_TABLE_ROWS)
            }
            "w:tbl" -> {
                tableDepth--
                if (tableDepth == 0) {
                    // tableUsable folds in the row cap: past it rows stop being collected, so the table is
                    // dropped whole rather than emitted partial.
                    if (grid.rows.isNotEmpty() && tableUsable) blocks += grid.emit()
                    grid.reset()
                }
            }
            "w:p" -> when {
                tableDepth == 0 -> if (--paragraphDepth == 0) emitParagraph()
                inCell -> endCellParagraph()
            }
            "w:footnote", "w:endnote" -> endNote()
        }
    }

    /** A field whose instruction says HYPERLINK: its result runs, until the field ends, are the link text. */
    private fun startField(fieldText: String) {
        val url = HYPERLINK_FIELD.find(fieldText)?.groupValues?.get(1)
        val safe = safeLink(url)
        if (safe != null) {
            fieldLink = safe
            link = safe
        }
    }

    private fun endField() {
        if (fieldLink != null) {
            fieldLink = null
            link = null
        }
    }

    private fun imageWidthDp(): Float? =
        imageWidthEmu?.let { (it / EMU_PER_DP).toFloat() }?.takeIf { it > 0f }

    /** The marker goes into the text now; the note itself is read from its own part and appended at the end. */
    private fun addNoteReference(p: XmlPullParser, endnote: Boolean) {
        val id = p.attr("w:id") ?: return
        val label = if (endnote) formatListNumber(++endnoteCount, "lowerRoman") else (++footnoteCount).toString()
        noteRefs += NoteRef(endnote, id, label)
        appendText(label, element = true, withFormat = format().copy(script = ScriptShift.SUPER))
    }

    /**
     * Gathers the paragraphs a `w:footnote` produced into one note, and takes them back out of
     * [blocks]: a note belongs after the body, in the order it was referenced, not where its part has it.
     */
    private fun endNote() {
        val id = noteId
        noteId = null
        val collected = ArrayList<OfficeBlock>(blocks.subList(noteStart, blocks.size))
        while (blocks.size > noteStart) blocks.removeAt(blocks.lastIndex)
        if (id == null || noteIsSeparator) return
        val parts = collected.mapNotNull { block ->
            when (block) {
                is OfficeBlock.Paragraph -> RichSnapshot(block.text, block.spans)
                is OfficeBlock.Heading -> RichSnapshot(block.text, block.spans)
                is OfficeBlock.ListItem -> RichSnapshot(
                    "${block.marker} ${block.text}",
                    shiftSpans(block.spans, block.marker.length + 1),
                )
                else -> null
            }
        }
        val joined = RichTextBuilder()
        parts.forEachIndexed { index, part ->
            if (index > 0) joined.append("\n")
            joined.appendRich(part.text, part.spans)
        }
        notes[id] = joined.snapshot { it.isWhitespace() }
    }

    /** The list marker this paragraph earns, or null if it is not in a list. Advances the list. */
    private fun markerFor(resolved: ParaStyle): String? {
        val numId = para.numId ?: resolved.numId ?: return null
        if (numId == 0) return null // an explicit "no list", which overrides one the style gave
        return counters.next(numId, para.numLevel ?: resolved.numLevel ?: 0)
    }

    private fun emitParagraph() {
        // A blank list paragraph is not numbered: the counter advances for what is shown.
        if (!paragraph.isBlank() && blocks.size < OfficeTextExtractor.MAX_BLOCKS) {
            val resolved = styles.paragraph(para.styleId)
            val level = styles.headingLevel(para.styleId, para.outline)
            val align = para.align ?: resolved.align ?: BlockAlign.START
            val marker = markerFor(resolved)
            // A numbered heading ("2.1 Method") reads as part of the heading, not as a list item.
            if (level > 0 && !marker.isNullOrEmpty()) paragraph.insert(0, "$marker ")
            val snapshot = paragraph.snapshot()
            blocks += when {
                level > 0 -> OfficeBlock.Heading(snapshot.text, level, snapshot.spans, align)
                marker != null -> OfficeBlock.ListItem(
                    snapshot.text,
                    marker,
                    (para.numLevel ?: resolved.numLevel ?: 0).coerceIn(0, MAX_LIST_LEVEL),
                    snapshot.spans,
                    align,
                )
                else -> OfficeBlock.Paragraph(
                    snapshot.text,
                    snapshot.spans,
                    align,
                    indentLevel(para.indentTwips ?: resolved.indentTwips),
                )
            }
        }
        paragraph.clear()
        para = ParaState()
    }

    /**
     * A cell draws as lines of text, so a list item there carries its marker in the text. Alignment is
     * the first paragraph's: a column of figures is right-aligned paragraph by paragraph.
     */
    private fun endCellParagraph() {
        if (tableDepth == 1) {
            val resolved = styles.paragraph(para.styleId)
            if (cellAlign == null) cellAlign = para.align ?: resolved.align ?: BlockAlign.START
            if (cell.length > cellParagraphStart) {
                val marker = markerFor(resolved)
                if (!marker.isNullOrEmpty()) cell.insert(cellParagraphStart, "$marker ")
            }
        }
        cell.append("\n") // one newline between a cell's paragraphs
    }
}
