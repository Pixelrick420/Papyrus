package com.papyrus.app.viewer

import com.papyrus.app.viewer.DocRichFixtures.LevelOverride
import com.papyrus.app.viewer.DocRichFixtures.ListDef
import com.papyrus.app.viewer.DocRichFixtures.ListInstance
import com.papyrus.app.viewer.DocRichFixtures.ListLevel
import com.papyrus.app.viewer.DocRichFixtures.Para
import com.papyrus.app.viewer.DocRichFixtures.Style
import com.papyrus.app.viewer.DocRichFixtures.bullets
import com.papyrus.app.viewer.DocRichFixtures.item
import com.papyrus.app.viewer.DocRichFixtures.numbered
import org.junit.Assert.assertEquals
import org.junit.Test

class DocListTest {

    private fun extract(bytes: ByteArray) = DocTextExtractor.extract(DocFixtures.streamFactory(bytes))

    private fun markers(blocks: List<OfficeBlock>) =
        blocks.map { (it as OfficeBlock.ListItem).marker }

    @Test
    fun bulletsStepDownWithTheLevel() {
        val blocks = extract(
            DocRichFixtures.doc(
                listOf(item("one", 1), item("two", 1, ilvl = 1), item("three", 1, ilvl = 2), item("four", 1, ilvl = 3)),
                lists = listOf(bullets(10)),
                instances = listOf(ListInstance(10)),
            ),
        )
        assertEquals(
            listOf(
                OfficeBlock.ListItem("one", "•", 0),
                OfficeBlock.ListItem("two", "◦", 1),
                OfficeBlock.ListItem("three", "▪", 2),
                OfficeBlock.ListItem("four", "•", 3),
            ),
            blocks,
        )
    }

    @Test
    fun aBulletTheDocumentChoseIsKept() {
        val star = ListDef(10, List(DocRichFixtures.LEVELS) { ListLevel(DocRichFixtures.NFC_BULLET, "★") })
        val blocks = extract(DocRichFixtures.doc(listOf(item("x", 1)), lists = listOf(star), instances = listOf(ListInstance(10))))
        assertEquals(listOf(OfficeBlock.ListItem("x", "★", 0)), blocks)
    }

    @Test
    fun numbersCountUpAndNestedLevelsRestart() {
        val blocks = extract(
            DocRichFixtures.doc(
                listOf(
                    item("a", 1), item("a.a", 1, 1), item("a.b", 1, 1), item("a.b.i", 1, 2),
                    item("b", 1), item("b.a", 1, 1), item("c", 1),
                ),
                lists = listOf(numbered(10)),
                instances = listOf(ListInstance(10)),
            ),
        )
        assertEquals(listOf("1.", "a)", "b)", "i.", "2.", "a)", "3."), markers(blocks))
        assertEquals(listOf(0, 1, 1, 2, 0, 1, 0), blocks.map { (it as OfficeBlock.ListItem).level })
    }

    @Test
    fun numberingContinuesAcrossAPlainParagraph() {
        val blocks = extract(
            DocRichFixtures.doc(
                listOf(item("one", 1), Para("interlude"), item("two", 1)),
                lists = listOf(numbered(10)),
                instances = listOf(ListInstance(10)),
            ),
        )
        assertEquals(
            listOf(OfficeBlock.ListItem("one", "1.", 0), OfficeBlock.Paragraph("interlude"), OfficeBlock.ListItem("two", "2.", 0)),
            blocks,
        )
    }

    @Test
    fun twoInstancesOfOneListShareItsCount() {
        val blocks = extract(
            DocRichFixtures.doc(
                listOf(item("one", 1), item("two", 2), item("three", 1)),
                lists = listOf(numbered(10)),
                instances = listOf(ListInstance(10), ListInstance(10)),
            ),
        )
        assertEquals(listOf("1.", "2.", "3."), markers(blocks))
    }

    @Test
    fun anInstanceThatRestartsALevelStartsOverAtTheNumberItNames() {
        val blocks = extract(
            DocRichFixtures.doc(
                listOf(item("one", 1), item("two", 1), item("fresh", 2), item("next", 2), item("late", 3), item("again", 1)),
                lists = listOf(numbered(10)),
                instances = listOf(
                    ListInstance(10),
                    ListInstance(10, listOf(LevelOverride(0, startAt = 1))),
                    ListInstance(10, listOf(LevelOverride(0, startAt = 7))),
                ),
            ),
        )
        assertEquals(listOf("1.", "2.", "1.", "2.", "7.", "8."), markers(blocks))
    }

    @Test
    fun anInstanceCanReplaceALevelsFormatting() {
        val blocks = extract(
            DocRichFixtures.doc(
                listOf(item("first", 1), item("second", 1)),
                lists = listOf(numbered(10)),
                instances = listOf(ListInstance(10, listOf(LevelOverride(0, format = ListLevel(1, "Part \u0000:", start = 3))))),
            ),
        )
        assertEquals(listOf("Part III:", "Part IV:"), markers(blocks))
    }

    @Test
    fun aMultiLevelTemplateNumbersFromEveryLevelItNames() {
        val legal = ListDef(
            10,
            listOf(ListLevel(0, "\u0000."), ListLevel(0, "\u0000.\u0001"), ListLevel(0, "\u0000.\u0001.\u0002")) +
                (3 until DocRichFixtures.LEVELS).map { ListLevel(0, "x") },
        )
        val blocks = extract(
            DocRichFixtures.doc(
                listOf(item("h", 1), item("s", 1, 1), item("s", 1, 1), item("t", 1, 2), item("h", 1), item("s", 1, 1)),
                lists = listOf(legal),
                instances = listOf(ListInstance(10)),
            ),
        )
        assertEquals(listOf("1.", "1.1", "1.2", "1.2.1", "2.", "2.1"), markers(blocks))
    }

    @Test
    fun numberFormatsFollowTheirBinaryCodes() {
        fun one(nfc: Int) = ListDef(10, List(DocRichFixtures.LEVELS) { ListLevel(nfc, "\u0000.") })
        fun first(nfc: Int, n: Int): String {
            val paras = (1..n).map { item("p", 1) }
            val blocks = extract(DocRichFixtures.doc(paras, lists = listOf(one(nfc)), instances = listOf(ListInstance(10))))
            return (blocks.last() as OfficeBlock.ListItem).marker
        }
        assertEquals("IV.", first(1, 4))
        assertEquals("iv.", first(2, 4))
        assertEquals("C.", first(3, 3))
        assertEquals("c.", first(4, 3))
        assertEquals("03.", first(22, 3))
        // Formats this reader has no names for count in plain decimals.
        assertEquals("3.", first(5, 3))
    }

    @Test
    fun aLevelWithNoNumberIsAnItemWithAnEmptyMarkerThatDoesNotCount() {
        val unnumbered = ListDef(
            10,
            listOf(ListLevel(0, "\u0000."), ListLevel(DocRichFixtures.NFC_NONE, "")) +
                (2 until DocRichFixtures.LEVELS).map { ListLevel(0, "\u0000.") },
        )
        val blocks = extract(
            DocRichFixtures.doc(
                listOf(item("one", 1), item("aside", 1, 1), item("two", 1)),
                lists = listOf(unnumbered),
                instances = listOf(ListInstance(10)),
            ),
        )
        assertEquals(
            listOf(OfficeBlock.ListItem("one", "1.", 0), OfficeBlock.ListItem("aside", "", 1), OfficeBlock.ListItem("two", "2.", 0)),
            blocks,
        )
    }

    @Test
    fun aLevelPastTheLastShowsNoNumber() {
        val blocks = extract(
            DocRichFixtures.doc(
                listOf(item("one", 1), item("note", 1, ilvl = 12), item("two", 1)),
                lists = listOf(numbered(10)),
                instances = listOf(ListInstance(10)),
            ),
        )
        assertEquals(listOf("1.", "", "2."), markers(blocks))
    }

    @Test
    fun aLineBreakInAnItemContinuesItWithoutANewNumber() {
        val blocks = extract(
            DocRichFixtures.doc(
                listOf(item("first line\u000Bsecond line", 1), item("next", 1), item("tail\u000Cpage", 1)),
                lists = listOf(numbered(10)),
                instances = listOf(ListInstance(10)),
            ),
        )
        assertEquals(
            listOf(
                OfficeBlock.ListItem("first line", "1.", 0),
                OfficeBlock.ListItem("second line", "", 0),
                OfficeBlock.ListItem("next", "2.", 0),
                OfficeBlock.ListItem("tail", "3.", 0),
                OfficeBlock.ListItem("page", "", 0),
            ),
            blocks,
        )
    }

    @Test
    fun aBlankListParagraphIsNotNumbered() {
        val blocks = extract(
            DocRichFixtures.doc(
                listOf(item("", 1), item("real", 1)),
                lists = listOf(numbered(10)),
                instances = listOf(ListInstance(10)),
            ),
        )
        assertEquals(listOf(OfficeBlock.ListItem("real", "1.", 0)), blocks)
    }

    @Test
    fun aNumberedHeadingKeepsItsNumberInItsText() {
        val blocks = extract(
            DocRichFixtures.doc(
                listOf(item("Intro", 1, istd = 1), item("Scope", 1, istd = 1)),
                lists = listOf(numbered(10)),
                instances = listOf(ListInstance(10)),
            ),
        )
        assertEquals(listOf(OfficeBlock.Heading("1. Intro", 1), OfficeBlock.Heading("2. Scope", 1)), blocks)
    }

    @Test
    fun aStyleCanPutItsParagraphsInAList() {
        val styles = DocRichFixtures.DEFAULT_STYLES + Style(
            "List Bullet",
            sti = 0x0FFE,
            base = 0,
            papx = DocRichFixtures.grpprl(DocRichFixtures.sprmWord(DocRichFixtures.PILFO, 1)),
        )
        val blocks = extract(
            DocRichFixtures.doc(
                listOf(Para("styled", istd = 4), Para("plain")),
                styles = styles,
                lists = listOf(bullets(10)),
                instances = listOf(ListInstance(10)),
            ),
        )
        assertEquals(listOf(OfficeBlock.ListItem("styled", "•", 0), OfficeBlock.Paragraph("plain")), blocks)
    }

    @Test
    fun anInstanceThatNamesNoListLeavesTheParagraphPlain() {
        val blocks = extract(
            DocRichFixtures.doc(
                listOf(item("orphan", 1), item("past the end", 9)),
                lists = listOf(numbered(10)),
                instances = listOf(ListInstance(99)),
            ),
        )
        assertEquals(listOf(OfficeBlock.Paragraph("orphan"), OfficeBlock.Paragraph("past the end")), blocks)
    }

    @Test
    fun aListItemInATableCellShowsItsMarkerInTheCellText() {
        val blocks = extract(
            DocRichFixtures.doc(
                listOf(
                    item("one", 1, mark = '\u0007'),
                    item("two", 1, mark = '\u0007'),
                    Para("", mark = '\u0007'),
                ),
                lists = listOf(numbered(10)),
                instances = listOf(ListInstance(10)),
            ),
        )
        assertEquals(
            listOf(OfficeBlock.Table(listOf(listOf(OfficeCell("1. one", column = 0), OfficeCell("2. two", column = 1))))),
            blocks,
        )
    }

    @Test
    fun listsThatAreUnreadableLeaveTheTextAndDropTheNumbers() {
        val good = DocRichFixtures.doc(listOf(item("one", 1), item("two", 1)), lists = listOf(numbered(10)), instances = listOf(ListInstance(10)))
        assertEquals(listOf("1.", "2."), markers(extract(good)))
        // A PlfLst that claims 40,000 lists in 86 bytes, and a PlfLfo shorter than its own count.
        val hostileLst = patchTable(good, pair = 73) { table, fc -> table[fc] = 0x40; table[fc + 1] = 0x9C.toByte() }
        assertEquals(listOf(OfficeBlock.Paragraph("one"), OfficeBlock.Paragraph("two")), extract(hostileLst))
        val hostileLfo = patchTable(good, pair = 74) { table, fc -> table[fc + 3] = 0x7F }
        assertEquals(listOf(OfficeBlock.Paragraph("one"), OfficeBlock.Paragraph("two")), extract(hostileLfo))
    }

    @Test
    fun listTablesCutAtAnyLengthParseWithoutFailing() {
        val whole = DocRichFixtures.listBytes(
            listOf(numbered(10), bullets(11)),
            listOf(ListInstance(10, listOf(LevelOverride(0, startAt = 4, format = ListLevel(0, "\u0000)")))), ListInstance(11)),
        )
        val tables = DocListTables.parse(whole.lst, whole.lstLcb, whole.lfo)
        assertEquals("4)", DocListCounters(tables).next(1, 0))
        assertEquals("•", DocListCounters(tables).next(2, 0))
        for (n in 0..whole.lst.size) DocListTables.parse(whole.lst.copyOf(n), minOf(n, whole.lstLcb), whole.lfo)
        for (n in 0..whole.lfo.size) DocListTables.parse(whole.lst, whole.lstLcb, whole.lfo.copyOf(n))
    }

    private fun patchTable(doc: ByteArray, pair: Int, edit: (ByteArray, Int) -> Unit): ByteArray {
        val cfb = CompoundFile(DocFixtures.streamFactory(doc), doc.size.toLong())
        val word = cfb.read("WordDocument", 0, cfb.streamSize("WordDocument")!!.toInt())
        val table = cfb.read("0Table", 0, cfb.streamSize("0Table")!!.toInt())
        edit(table, u32(word, 0x9A + pair * 8).toInt())
        return DocFixtures.compound(linkedMapOf("WordDocument" to word, "0Table" to table))
    }
}
