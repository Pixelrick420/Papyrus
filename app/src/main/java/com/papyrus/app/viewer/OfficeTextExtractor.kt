package com.papyrus.app.viewer

import android.util.Xml
import com.papyrus.app.data.DocumentFormat
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * A single table cell. A cell *covered* by a rowspan from a row above is absent entirely: the
 * renderer reads the merge off the origin cell, so a repeated blank would shift every later cell in
 * the row and draw a border where the merge should be seamless.
 */
data class OfficeCell(
    val text: String,
    val column: Int = 0,
    val colspan: Int = 1,
    val rowspan: Int = 1,
) {
    /** Index of the last column this cell occupies; inclusive. */
    val lastColumn: Int get() = column + colspan - 1
}

/**
 * One laid-out piece of an Office document. Not pixel-accurate to Word/LibreOffice: headings,
 * paragraphs, tables and inline images in document order is the fidelity targeted.
 */
sealed interface OfficeBlock {
    data class Heading(val text: String, val level: Int) : OfficeBlock
    data class Paragraph(val text: String) : OfficeBlock
    /**
     * A table flattened into rows. [OfficeCell.column] is where a cell *starts*, so a cell covered by
     * a rowspan above is absent from its row rather than repeated as empty. Nested tables are
     * flattened into the surrounding block list.
     */
    data class Table(val rows: List<List<OfficeCell>>) : OfficeBlock
    /** Media written to [file]; the viewer downsamples on decode, so the bytes are not held resident. */
    data class Image(val file: File, val mimeType: String) : OfficeBlock
}

/**
 * Dependency-free extraction for .docx / .odt, both ZIP + XML, via java.util.zip and the platform
 * XmlPullParser. The archive is walked twice through [openStream] rather than buffered whole, so
 * peak memory stays proportional to the body XML and media is read once.
 */
class OfficeTextExtractor(
    private val mediaDir: File?,
    private val mimeByName: (String) -> String = ::guessMimeType,
    /** Injected because [Xml.newPullParser] is a platform stub, so a JVM unit test would get null instead of a parser. */
    private val newParserFactory: () -> XmlPullParser = { Xml.newPullParser() },
) {

    /** [openStream] must return a fresh stream on every call, once for the body and once for the media. The caller closes it. */
    fun extract(openStream: () -> InputStream, format: DocumentFormat): List<OfficeBlock> {
        val bodyEntry = when (format) {
            DocumentFormat.DOCX -> DOCX_BODY
            DocumentFormat.ODT -> ODT_BODY
            else -> throw IOException("Unsupported office format: $format")
        }
        val relsEntry = if (format == DocumentFormat.DOCX) DOCX_RELS else null

        val body: ByteArray
        val rels: Map<String, String>
        openStream().use { input ->
            val found = HashMap<String, ByteArray>()
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (entry.name == bodyEntry || entry.name == relsEntry) {
                        found[entry.name] = zip.readBytes()
                    }
                }
            }
            body = found[bodyEntry] ?: throw IOException("$bodyEntry not found: not a valid document")
            rels = relsEntry?.let { parseRels(found[it]) } ?: emptyMap()
        }

        val pending = mutableListOf<PendingImage>()
        val blocks = parseBody(body, format, rels, pending)

        if (pending.isNotEmpty() && mediaDir != null) {
            val wanted = pending.mapTo(HashSet()) { it.zipName }
            val budget = MediaBudget()
            // zipName -> the image block it produced, so one entry referenced from several places inserts one
            // block per *reference* while its bytes are read and written once.
            val written = HashMap<String, OfficeBlock.Image>()
            readMedia(openStream, wanted, budget) { zipName, bytes ->
                val file = writeMedia(zipName, bytes) ?: return@readMedia
                written[zipName] = OfficeBlock.Image(file, mimeByName(zipName))
            }
            // Insert at the recorded position so images land where they appeared rather than being appended.
            var inserted = 0
            pending.forEach { image ->
                // A dangling relationship or a budget rejection lands here as null; the text still renders.
                val block = written[image.zipName] ?: return@forEach
                if (blocks.size >= MAX_BLOCKS) return@forEach
                // `index` was recorded against the list before any image went in, so every image already
                // inserted has pushed this position down by one. Without the offset, images drifted upward
                // and ended up clustered together near the first one.
                blocks.add((image.index + inserted).coerceIn(0, blocks.size), block)
                inserted++
            }
        }
        return blocks
    }

    private class PendingImage(val zipName: String, val index: Int)

    /** The anchoring paragraph's state while a text box's own paragraphs are parsed. */
    private class SetAsideParagraph(val text: String, val depth: Int, val headingLevel: Int)

    /**
     * Byte budget so a document full of photographs cannot fill the disk or the heap. Reserve then
     * consume: an entry's size is known before reading only when the ZIP had a central directory, so
     * [canReserve] is the cheap pre-check and [consume] the authoritative charge.
     */
    private class MediaBudget {
        private var remaining = MAX_TOTAL_MEDIA_BYTES
        private var count = 0

        /** True when an entry of [declaredSize] (-1 when unknown) could plausibly be accepted. */
        fun canReserve(declaredSize: Long): Boolean {
            if (count >= MAX_IMAGES) return false
            if (declaredSize > MAX_IMAGE_BYTES) return false
            if (declaredSize > remaining) return false
            return true
        }

        fun consume(size: Int): Boolean {
            if (size > MAX_IMAGE_BYTES || size > remaining) return false
            remaining -= size
            count++
            return true
        }
    }

    private fun writeMedia(zipName: String, bytes: ByteArray): File? {
        val dir = mediaDir ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null
        // Two entries in one archive can sanitise to the same name; disambiguate rather than clobber.
        val target = File(dir, sanitiseName(zipName))
        if (!target.exists()) return target.writeOrNull(bytes)
        val base = target.nameWithoutExtension
        val ext = target.extension.ifEmpty { "bin" }
        var suffix = 1
        while (true) {
            val candidate = File(dir, "$base-$suffix.$ext")
            if (!candidate.exists()) return candidate.writeOrNull(bytes)
            suffix++
        }
    }

    private fun File.writeOrNull(bytes: ByteArray): File? = try {
        writeBytes(bytes)
        this
    } catch (_: IOException) {
        null
    }

    /**
     * Streams the wanted entries straight to [sink], charging the budget as each arrives rather than
     * buffering them all, which would let 200 images allocate 200 x [MAX_IMAGE_BYTES] first. The
     * per-entry cap counts bytes instead of trusting `entry.size`: a streamed entry in a ZIP without a
     * central directory reports -1, and `readBytes()` on it would read without bound.
     */
    private fun readMedia(
        openStream: () -> InputStream,
        wanted: Set<String>,
        budget: MediaBudget,
        sink: (zipName: String, bytes: ByteArray) -> Unit,
    ) {
        if (wanted.isEmpty()) return
        openStream().use { input ->
            ZipInputStream(input).use { zip ->
                val buffer = ByteArray(MEDIA_COPY_BUFFER)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || entry.name !in wanted) continue
                    // Reserve before reading: an entry the budget will never accept must not be decoded at all.
                    if (!budget.canReserve(entry.size)) continue
                    val bytes = readBounded(zip, entry.size, buffer) ?: continue
                    if (!budget.consume(bytes.size)) continue
                    sink(entry.name, bytes)
                }
            }
        }
    }

    /** Reads one entry, capped at the smaller of its declared size and [MAX_IMAGE_BYTES]; null if it exceeds that. */
    private fun readBounded(zip: ZipInputStream, declaredSize: Long, buffer: ByteArray): ByteArray? {
        val out = ByteArrayOutputStream(
            if (declaredSize in 1..MAX_IMAGE_BYTES) declaredSize.toInt() else MEDIA_COPY_BUFFER,
        )
        while (true) {
            // A sized entry that yields more than it declared is rejected, not truncated into a corrupt image.
            val overBudget = out.size() >= MAX_IMAGE_BYTES
            val overDeclared = declaredSize >= 0 && out.size() >= declaredSize
            if (overBudget) return null
            if (overDeclared) return out.toByteArray()
            val n = zip.read(buffer)
            if (n < 0) return out.toByteArray()
            out.write(buffer, 0, n)
        }
    }

    private fun newParser(stream: InputStream): XmlPullParser =
        newParserFactory().apply {
            // Namespaces stay in the raw name ("a:blip"); the branch table matches on it.
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(stream, null)
        }

    /** `rId7 -> word/media/image3.png` for a DOCX body. Empty map when the rels part is absent. */
    private fun parseRels(xml: ByteArray?): Map<String, String> {
        if (xml == null || xml.isEmpty()) return emptyMap()
        val out = HashMap<String, String>()
        val p = newParser(xml.inputStream())
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name == "Relationship") {
                val id = p.getAttributeValue(null, "Id")
                val target = p.getAttributeValue(null, "Target")
                if (id != null && target != null) out[id] = resolveDocxTarget(target)
            }
            event = p.next()
        }
        return out
    }

    /** Relationship targets are relative to `word/`, though some producers write an absolute `/word/...`. */
    private fun resolveDocxTarget(target: String): String = when {
        target.startsWith("/") -> target.removePrefix("/")
        target.startsWith("$DOCX_DIR/") -> target
        else -> "$DOCX_DIR/$target"
    }

    private fun parseBody(
        body: ByteArray,
        format: DocumentFormat,
        rels: Map<String, String>,
        pending: MutableList<PendingImage>,
    ): MutableList<OfficeBlock> =
        if (format == DocumentFormat.DOCX) parseDocx(body, rels, pending)
        else parseOdt(body, pending)

    /**
     * A grid being built for the table currently open. Vertical merges are the awkward part: `w:vMerge`
     * continuation lives in a *later* row at the same column, so the origin cell is not reachable from
     * the row being parsed; [Slot] is mutable, reached by that continuation row via column through
     * [openCellAt]. A continuation records a non-origin placeholder to hold the grid position, and
     * [emit] drops it -- emitting it would duplicate the merged text and shift every later cell.
     */
    private class Grid {
        val rows = mutableListOf<List<Slot>>()
        var row = mutableListOf<Slot>()
        /** grid column -> the Slot that started the vertical merge, for columns merged so far. */
        private val mergeOrigins = HashMap<Int, Slot>()

        val isEmpty: Boolean get() = row.isEmpty()

        fun startRow() {
            row = mutableListOf()
        }

        /**
         * Closes the current row of the **outermost** table. Only depth-1 rows are committed, so
         * pathologically nested input cannot multiply the row count; nested cell text survives,
         * routed into the enclosing outer cell's buffer. False once the row cap is reached, so the
         * caller can drop the whole table rather than emit a truncated one.
         */
        fun commitRow(rowLimit: Int): Boolean {
            if (row.isNotEmpty() && rows.size < rowLimit) rows += row.toList()
            // A merge that no longer reaches this row is over; without pruning, a ragged table could
            // resurrect a stale origin and hand a fresh cell an inflated rowspan.
            val placed = row.mapTo(HashSet()) { it.column }
            mergeOrigins.keys.retainAll(placed)
            row = mutableListOf()
            return rows.size < rowLimit
        }

        /** Records the cell ending here; only RESTART may open a new merge origin, anything else ends the merge at this column. */
        fun openCellAt(column: Int, text: String, colspan: Int, merge: MergeState): Slot {
            val origin = mergeOrigins[column]
            if (merge == MergeState.CONTINUE && origin != null) {
                origin.rowspan++
                val placeholder = Slot(column = column, colspan = colspan, origin = false)
                row += placeholder
                return placeholder
            }
            val slot = Slot(text = text, column = column, colspan = colspan)
            row += slot
            // A plain cell ends any merge open at this column; a RESTART begins a new one.
            if (merge == MergeState.RESTART) mergeOrigins[column] = slot else mergeOrigins.remove(column)
            return slot
        }

        /** Grows the row's last cell to absorb an ODT `table:covered-table-cell` beside it. */
        fun widenLastColumn() {
            val last = row.lastOrNull() ?: return
            if (!last.origin) return
            row[row.lastIndex] = last.copy(colspan = (last.colspan + 1).coerceAtMost(MAX_MERGE_SPAN))
        }

        fun emit(): OfficeBlock.Table =
            OfficeBlock.Table(rows.map { r -> r.filter { it.origin }.map { it.toCell() } })

        fun reset() {
            rows.clear()
            row = mutableListOf()
            mergeOrigins.clear()
        }
    }

    private enum class MergeState { NONE, RESTART, CONTINUE }

    private data class Slot(
        var text: String = "",
        val column: Int = 0,
        var colspan: Int = 1,
        var rowspan: Int = 1,
        val origin: Boolean = true,
    ) {
        val lastColumn: Int get() = column + colspan - 1

        fun toCell() = OfficeCell(text, column, colspan, rowspan)
    }

    private fun parseDocx(
        body: ByteArray,
        rels: Map<String, String>,
        pending: MutableList<PendingImage>,
    ): MutableList<OfficeBlock> {
        val p = newParser(body.inputStream())
        val blocks = mutableListOf<OfficeBlock>()
        val paragraph = StringBuilder()
        var paragraphDepth = 0
        var headingLevel = 0
        var inText = false
        var tableDepth = 0
        val grid = Grid()
        var column = 0
        var inCell = false
        var cellBuffer = StringBuilder()
        var cellColspan = 1
        var cellMerge = MergeState.NONE
        var tableUsable = true
        // `w:tab` is also a tab-stop *definition* inside `w:tabs`; only a run's `w:tab` is a character.
        var inTabStops = false
        // Inside `mc:Fallback`: the legacy copy of what `mc:Choice` already said.
        var fallbackDepth = 0
        // A text box's paragraphs nest inside the paragraph that anchors it; they are set aside so the
        // box reads as its own paragraphs instead of being glued onto the anchor's text.
        val textBoxes = ArrayDeque<SetAsideParagraph>()

        fun emitParagraph() {
            val text = paragraph.toString()
            if (text.isNotBlank() && blocks.size < MAX_BLOCKS) {
                blocks += if (headingLevel > 0) OfficeBlock.Heading(text, headingLevel)
                else OfficeBlock.Paragraph(text)
            }
            paragraph.setLength(0)
            headingLevel = 0
        }

        /**
         * Parser text goes to the cell buffer inside a `w:tc` and to the paragraph otherwise, but
         * only from inside a `w:t`: whitespace between structural tags is layout, not content, and
         * pretty-printed producers indent `<w:tc>` away from its first `<w:p>`, which would pad every
         * cell. [element] marks characters an element contributes (a tab, a break) -- content
         * regardless of the surrounding `w:t` state.
         */
        fun appendText(raw: String, element: Boolean = false) {
            if (inText || element) {
                if (inCell) cellBuffer.append(raw) else paragraph.append(raw)
            }
        }

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT && blocks.size < MAX_BLOCKS) {
            val isFallbackTag = (event == XmlPullParser.START_TAG || event == XmlPullParser.END_TAG) &&
                p.name == "mc:Fallback"
            if (isFallbackTag) {
                if (event == XmlPullParser.START_TAG) fallbackDepth++ else if (fallbackDepth > 0) fallbackDepth--
            } else if (fallbackDepth == 0) when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
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
                        inCell = true
                        cellBuffer = StringBuilder()
                        cellColspan = 1
                        cellMerge = MergeState.NONE
                    } else if (tableDepth > 1) {
                        // A nested cell sits inside an outer cell that is still open, so `inCell` stays true and its
                        // text lands in the outer cell's buffer. The buffer is not reset: that text
                        // belongs to the outer cell.
                    }
                    "w:gridSpan" -> if (inCell && tableDepth == 1) {
                        cellColspan = p.getAttributeValue(null, "w:val")?.toIntOrNull()
                            ?.coerceIn(1, MAX_MERGE_SPAN) ?: 1
                    }
                    "w:vMerge" -> if (inCell && tableDepth == 1) {
                        // val="restart" opens a merge, "continue" extends one, and no val at all means continue.
                        cellMerge = when (p.getAttributeValue(null, "w:val")) {
                            "restart" -> MergeState.RESTART
                            null, "continue" -> MergeState.CONTINUE
                            else -> MergeState.NONE
                        }
                    }
                    "w:p" -> if (tableDepth == 0 && paragraphDepth++ == 0) {
                        paragraph.setLength(0)
                        headingLevel = 0
                    }
                    "w:pStyle" -> if (tableDepth == 0) {
                        headingLevel = p.getAttributeValue(null, "w:val").orEmpty().toHeadingLevel()
                    }
                    // a:blip is DrawingML; v:imagedata is the legacy VML fallback Word still emits.
                    "a:blip", "v:imagedata" -> if (tableDepth == 0) {
                        val id = p.getAttributeValue(null, "r:embed")
                            ?: p.getAttributeValue(null, "r:id")
                        rels[id]?.let { target -> pending += PendingImage(target, blocks.size) }
                    }
                    "w:t" -> inText = true
                    "w:tabs" -> inTabStops = true
                    "w:tab" -> if (!inTabStops) appendText("\t", element = true)
                    "w:ptab" -> appendText("\t", element = true)
                    "w:noBreakHyphen" -> appendText("-", element = true)
                    "w:br", "w:cr" -> appendText("\n", element = true)
                    "w:txbxContent" -> if (tableDepth == 0) {
                        textBoxes.addLast(SetAsideParagraph(paragraph.toString(), paragraphDepth, headingLevel))
                        paragraph.setLength(0)
                        paragraphDepth = 0
                        headingLevel = 0
                    }
                }

                XmlPullParser.TEXT -> appendText(p.text)

                XmlPullParser.END_TAG -> when (p.name) {
                    "w:t" -> inText = false
                    "w:tabs" -> inTabStops = false
                    "w:txbxContent" -> if (tableDepth == 0 && textBoxes.isNotEmpty()) {
                        emitParagraph() // text in the box that no `</w:p>` closed
                        val host = textBoxes.removeLast()
                        paragraph.append(host.text)
                        paragraphDepth = host.depth
                        headingLevel = host.headingLevel
                    }
                    "w:tc" -> if (inCell && tableDepth == 1) {
                        inCell = false
                        grid.openCellAt(column, cellBuffer.toString().trim('\n'), cellColspan, cellMerge)
                        column += cellColspan
                        cellBuffer = StringBuilder()
                    }
                    "w:tr" -> if (tableDepth == 1) {
                        tableUsable = grid.commitRow(rowLimit = MAX_TABLE_ROWS)
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
                        inCell -> cellBuffer.append('\n') // one newline between a cell's paragraphs
                    }
                }
            }
            event = p.next()
        }
        return blocks
    }

    private fun String.toHeadingLevel(): Int = when {
        equals("Title", true) -> 1
        startsWith("Heading", true) -> substringAfter("Heading", "1").toIntOrNull()?.coerceIn(1, 6) ?: 1
        else -> 0
    }

    private fun parseOdt(body: ByteArray, pending: MutableList<PendingImage>): MutableList<OfficeBlock> {
        val p = newParser(body.inputStream())
        val blocks = mutableListOf<OfficeBlock>()
        val paragraph = StringBuilder()
        var paragraphDepth = 0
        var headingLevel = 0
        var tableDepth = 0
        val grid = Grid()
        var inCell = false
        var cellBuffer = StringBuilder()
        // ODT marks a vertical merge with a sibling `table:covered-table-cell` rather than an
        // attribute, so there is no merge state to track here, only a column cursor to advance.
        var column = 0
        var tableUsable = true
        // How many <text:p>/<text:h> are open: ODF producers indent their XML just like DOCX ones, so only
        // text inside a paragraph element is content. A depth, not a flag: a frame or text box nests a
        // paragraph inside one, and the inner end tag must not switch off the outer paragraph's remaining text.
        var paragraphElementDepth = 0
        // Footnote bodies, comments and deleted text sit *inside* the paragraph they annotate. Their
        // text is not part of it, and (for comments) includes the author and timestamp.
        var skipDepth = 0
        val textBoxes = ArrayDeque<SetAsideOdtParagraph>()
        // Merge geometry. Real files declare spans as attributes, then add covered cells as placeholders.
        var cellColspan = 1
        var cellRowspan = 1
        var cellRepeat = 1
        var rowIndex = -1
        var tableColumns = 0
        var horizontalCoversLeft = 0
        // column -> last row index that a rowspan from above still covers
        val verticalCoveredUntil = HashMap<Int, Int>()

        fun emitParagraph() {
            val text = paragraph.toString()
            if (text.isNotBlank() && blocks.size < MAX_BLOCKS) {
                blocks += if (headingLevel > 0) OfficeBlock.Heading(text, headingLevel)
                else OfficeBlock.Paragraph(text)
            }
            paragraph.setLength(0)
            headingLevel = 0
        }

        fun appendText(text: String) {
            if (paragraphElementDepth == 0) return
            if (inCell) cellBuffer.append(text) else paragraph.append(text)
        }

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT && blocks.size < MAX_BLOCKS) {
            val skippedTag = (event == XmlPullParser.START_TAG || event == XmlPullParser.END_TAG) &&
                p.name in ODT_SKIPPED
            if (skippedTag) {
                if (event == XmlPullParser.START_TAG) skipDepth++ else if (skipDepth > 0) skipDepth--
            } else if (skipDepth == 0) when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "table:table" -> {
                        if (tableDepth == 0) {
                            emitParagraph()
                            grid.reset()
                            tableUsable = true
                            rowIndex = -1
                            tableColumns = 0
                            verticalCoveredUntil.clear()
                        }
                        tableDepth++
                    }
                    "table:table-column" -> if (tableDepth == 1) {
                        tableColumns += p.intAttr("table:number-columns-repeated")
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
                        cellBuffer = StringBuilder()
                        cellColspan = p.intAttr("table:number-columns-spanned")
                        cellRowspan = p.intAttr("table:number-rows-spanned")
                        cellRepeat = p.intAttr("table:number-columns-repeated")
                    }
                    "table:covered-table-cell" -> if (tableDepth == 1) {
                        repeat(p.intAttr("table:number-columns-repeated")) {
                            when {
                                // Right of a cell that declared `number-columns-spanned`: the cell already carries
                                // the span and moved the column cursor past it, so there is nothing left to do.
                                horizontalCoversLeft > 0 -> horizontalCoversLeft--
                                // Under a rowspan from above: absent, as in DOCX, but it does occupy its column.
                                // Widening the left neighbour here used to make a vertical merge in a middle
                                // column stretch the cell beside it.
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
                        textBoxes.addLast(
                            SetAsideOdtParagraph(paragraph.toString(), paragraphDepth, headingLevel, paragraphElementDepth),
                        )
                        paragraph.setLength(0)
                        paragraphDepth = 0
                        headingLevel = 0
                        paragraphElementDepth = 0
                    }
                    "text:p", "text:h" -> {
                        paragraphElementDepth++
                        if (tableDepth == 0 && paragraphDepth++ == 0) {
                            paragraph.setLength(0)
                            headingLevel = if (p.name == "text:h") p.outlineLevel() else 0
                        }
                    }
                    "draw:image" -> if (tableDepth == 0) {
                        p.getAttributeValue(null, "xlink:href")
                            ?.let { href -> pending += PendingImage(href, blocks.size) }
                    }
                    "text:tab" -> appendText("\t")
                    "text:line-break" -> appendText("\n")
                    "text:s" -> repeat(p.getAttributeValue(null, "text:c")?.toIntOrNull() ?: 1) {
                        appendText(" ")
                    }
                }

                XmlPullParser.TEXT -> appendText(p.text)

                XmlPullParser.END_TAG -> when (p.name) {
                    "table:table-row" -> if (tableDepth == 1) {
                        tableUsable = grid.commitRow(rowLimit = MAX_TABLE_ROWS)
                    }
                    "table:table-cell" -> if (inCell && tableDepth == 1) {
                        inCell = false
                        val text = cellBuffer.toString().trim('\n')
                        // A repeat cannot run past the declared columns, or a spreadsheet-style
                        // "repeat 1000 empty cells" would add a thousand columns.
                        val room = if (tableColumns > 0) (tableColumns - column).coerceAtLeast(1) else cellRepeat
                        repeat(minOf(cellRepeat, room)) {
                            grid.openCellAt(column, text, cellColspan, MergeState.NONE).rowspan = cellRowspan
                            if (cellRowspan > 1) {
                                for (c in column until column + cellColspan) {
                                    verticalCoveredUntil[c] = rowIndex + cellRowspan - 1
                                }
                            }
                            column += cellColspan
                        }
                        horizontalCoversLeft = cellColspan - 1
                        cellBuffer = StringBuilder()
                    }
                    "draw:text-box" -> if (tableDepth == 0 && textBoxes.isNotEmpty()) {
                        emitParagraph() // text in the box that no `</text:p>` closed
                        val host = textBoxes.removeLast()
                        paragraph.append(host.text)
                        paragraphDepth = host.depth
                        headingLevel = host.headingLevel
                        paragraphElementDepth = host.elementDepth
                    }
                    "table:table" -> {
                        tableDepth--
                        if (tableDepth == 0) {
                            if (grid.rows.isNotEmpty() && tableUsable) blocks += grid.emit()
                            grid.reset()
                        }
                    }
                    "text:p", "text:h" -> {
                        if (paragraphElementDepth > 0) paragraphElementDepth--
                        when {
                            // One newline between a cell's paragraphs, as in DOCX: without it a cell holding two
                            // paragraphs, or an outer cell holding a nested table's cell, reads as one run-on line.
                            tableDepth == 0 -> if (--paragraphDepth == 0) emitParagraph()
                            inCell -> cellBuffer.append('\n')
                        }
                    }
                }
            }
            event = p.next()
        }
        return blocks
    }

    /** A positive count attribute, 1 when absent or unusable, capped so a bad file cannot ask for thousands. */
    private fun XmlPullParser.intAttr(name: String): Int =
        getAttributeValue(null, name)?.toIntOrNull()?.coerceIn(1, MAX_MERGE_SPAN) ?: 1

    /** The anchoring paragraph's state while a text box's own paragraphs are parsed. */
    private class SetAsideOdtParagraph(val text: String, val depth: Int, val headingLevel: Int, val elementDepth: Int)

    /** ODT heading depth comes from an automatic style, so the attribute sits on the element here. */
    private fun XmlPullParser.outlineLevel(): Int =
        getAttributeValue(null, "text:outline-level")?.toIntOrNull()?.coerceIn(1, 6) ?: 1

    companion object {
        private const val DOCX_DIR = "word"
        private const val DOCX_BODY = "word/document.xml"
        private const val DOCX_RELS = "word/_rels/document.xml.rels"
        private const val ODT_BODY = "content.xml"

        /** Subtrees whose text is annotation rather than body: footnotes and endnotes, comments, deleted text. */
        private val ODT_SKIPPED = setOf("text:note", "office:annotation", "text:tracked-changes")

        const val MAX_BLOCKS = 20_000
        const val MAX_TABLE_ROWS = 2_000
        const val MAX_IMAGES = 200
        const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
        const val MAX_TOTAL_MEDIA_BYTES = 24 * 1024 * 1024
        private const val MEDIA_COPY_BUFFER = 64 * 1024

        /** A single cell cannot usefully span more columns than this; a bigger value is a bad file. */
        private const val MAX_MERGE_SPAN = 64

        /** Zip-slip guard: media names come from the document, so never trust their path shape. */
        fun sanitiseName(zipName: String): String =
            zipName.substringAfterLast('/')
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .ifBlank { "image" }

        fun guessMimeType(name: String): String = when (name.substringAfterLast('.').lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            "webp" -> "image/webp"
            "svg" -> "image/svg+xml"
            "tif", "tiff" -> "image/tiff"
            "emf", "wmf" -> "image/x-wmf"
            else -> "image/*"
        }
    }
}
