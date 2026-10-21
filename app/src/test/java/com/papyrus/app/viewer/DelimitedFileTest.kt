package com.papyrus.app.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelimitedFileTest {

    private fun rows(text: String, delimiter: Char = ',') = DelimitedFile.parse(text, delimiter).rows

    @Test
    fun `plain rows split on the delimiter`() {
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), rows("a,b,c\n1,2,3"))
    }

    @Test
    fun `any delimiter splits`() {
        assertEquals(listOf(listOf("a", "b")), rows("a;b", ';'))
        assertEquals(listOf(listOf("a", "b")), rows("a\tb", '\t'))
        assertEquals(listOf(listOf("a", "b")), rows("a|b", '|'))
    }

    @Test
    fun `line breaks of every kind end a row, and a pair counts once`() {
        val expected = listOf(listOf("a"), listOf("b"), listOf("c"))

        assertEquals(expected, rows("a\nb\nc"))
        assertEquals(expected, rows("a\r\nb\r\nc"))
        assertEquals(expected, rows("a\rb\rc"))
        assertEquals(expected, rows("a\r\nb\nc\r"))
    }

    @Test
    fun `a final line break adds no row and blank lines are skipped`() {
        assertEquals(listOf(listOf("a", "b")), rows("a,b\n"))
        assertEquals(listOf(listOf("a"), listOf("b")), rows("a\n\n\r\n\nb\n\n"))
        assertEquals(emptyList<List<String>>(), rows(""))
        assertEquals(emptyList<List<String>>(), rows("\n\n"))
    }

    @Test
    fun `empty cells are kept and a row of only delimiters is a row`() {
        assertEquals(listOf(listOf("a", "", "c"), listOf("", "", ""), listOf("x", "")), rows("a,,c\n,,\nx,"))
    }

    @Test
    fun `a quoted cell holds the delimiter`() {
        assertEquals(listOf(listOf("Smith, John", "42")), rows("\"Smith, John\",42"))
        assertEquals(listOf(listOf("a;b", "c")), rows("\"a;b\";c", ';'))
    }

    @Test
    fun `a doubled quote is one quote`() {
        assertEquals(listOf(listOf("say \"hi\"", "x")), rows("\"say \"\"hi\"\"\",x"))
        assertEquals(listOf(listOf("\"")), rows("\"\"\"\""))
    }

    @Test
    fun `a quoted cell holds line breaks, each read as one newline`() {
        assertEquals(listOf(listOf("two\nlines", "x"), listOf("y", "z")), rows("\"two\nlines\",x\ny,z"))
        assertEquals(listOf(listOf("two\nlines", "x")), rows("\"two\r\nlines\",x"))
        assertEquals(listOf(listOf("two\nlines", "x")), rows("\"two\rlines\",x"))
    }

    @Test
    fun `a quoted empty cell is a cell and a line of one is a row`() {
        assertEquals(listOf(listOf("a", "", "b")), rows("a,\"\",b"))
        assertEquals(listOf(listOf(""), listOf("x")), rows("\"\"\nx"))
    }

    @Test
    fun `a quote inside an unquoted cell is text`() {
        assertEquals(listOf(listOf("5\" pipe", "x")), rows("5\" pipe,x"))
        assertEquals(listOf(listOf("ab\"cd", "x")), rows("ab\"cd,x"))
    }

    @Test
    fun `a quote after the start of a cell opens nothing, even after a space`() {
        // Excel reads ` "b,c"` as two cells too: only a cell's first character opens a quote.
        assertEquals(listOf(listOf("a", " \"b", "c\"")), rows("a, \"b,c\""))
    }

    @Test
    fun `characters after a closing quote stay in the cell`() {
        assertEquals(listOf(listOf("abcd", "x")), rows("\"ab\"cd,x"))
    }

    @Test
    fun `an unterminated quote runs to the end instead of failing`() {
        assertEquals(listOf(listOf("a", "b,c\nd,e")), rows("a,\"b,c\nd,e"))
    }

    @Test
    fun `whitespace in cells is kept`() {
        assertEquals(listOf(listOf(" a ", "b  c")), rows(" a ,b  c"))
    }

    @Test
    fun `ragged rows stay as long as they are`() {
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1"), listOf("x", "y")), rows("a,b,c\n1\nx,y"))
    }

    @Test
    fun `a byte-order mark is not part of the first cell`() {
        assertEquals(listOf(listOf("name", "age")), rows("\uFEFFname,age"))
        // Still a quote opener behind the mark.
        assertEquals(listOf(listOf("a,b", "c")), rows("\uFEFF\"a,b\",c"))
    }

    @Test
    fun `non-latin text is untouched`() {
        assertEquals(listOf(listOf("名前", "ജനനം", "😀")), rows("名前,ജനനം,😀"))
    }

    @Test
    fun `no row is ever empty`() {
        val tricky = "a,b\n\n,\n\"\"\n \n,,\r\n"
        assertTrue(rows(tricky).all { it.isNotEmpty() })
    }

    // --- the delimiter ------------------------------------------------------------------------

    private fun delimiter(name: String?, text: String) = DelimitedFile.delimiterFor(name, text)

    @Test
    fun `a tsv is tab-separated whatever it holds`() {
        assertEquals('\t', delimiter("data.tsv", "a,b,c\n1,2,3"))
        assertEquals('\t', delimiter("DATA.TSV", ""))
        assertEquals('\t', delimiter("a.b.tsv", "a;b"))
    }

    @Test
    fun `a csv of commas is read as commas`() {
        assertEquals(',', delimiter("a.csv", "id,name,city\n1,Ann,Oslo\n2,Bo,Rome"))
    }

    @Test
    fun `a csv of semicolons is found, as Excel writes it in many locales`() {
        assertEquals(';', delimiter("a.csv", "id;name;city\n1;Ann;Oslo\n2;Bo;Rome"))
    }

    @Test
    fun `decimal commas do not make a semicolon file a comma file`() {
        // Every row splits into four cells at commas too, but the header's words disagree with them.
        val text = "name;price;qty\nfoo;1,5;2,5\nbar;3,25;4,5\nbaz;9,99;1,5"

        assertEquals(';', delimiter("prices.csv", text))
        assertEquals(listOf("foo", "1,5", "2,5"), DelimitedFile.parse(text, ';').rows[1])
    }

    @Test
    fun `a tab-separated csv and a pipe-separated csv are found`() {
        assertEquals('\t', delimiter("a.csv", "id\tname\n1\tAnn\n2\tBo"))
        assertEquals('|', delimiter("a.csv", "id|name\n1|Ann\n2|Bo"))
    }

    @Test
    fun `commas in the prose of a tab file do not win`() {
        assertEquals('\t', delimiter("notes.csv", "id\ttext\n1\tred, green, blue\n2\tone, two\n3\tnone"))
    }

    @Test
    fun `quoted delimiters are not counted`() {
        assertEquals(',', delimiter("a.csv", "name,note\n\"A; B; C\",x\n\"D; E\",y"))
    }

    @Test
    fun `a file nothing explains stays comma-separated`() {
        assertEquals(',', delimiter("a.csv", "just\none\ncolumn"))
        assertEquals(',', delimiter("a.csv", ""))
        assertEquals(',', delimiter(null, "plain words with no structure"))
    }

    @Test
    fun `only the start of a long file is sniffed`() {
        val head = "a;b;c\n"
        val body = (1..50_000).joinToString("\n") { "1,2;3,4;5,6" }

        assertEquals(';', delimiter("a.csv", head + body))
    }

    // --- limits -------------------------------------------------------------------------------

    @Test
    fun `a file past the cell limit stops at a row boundary and says so`() {
        val text = (1..10).joinToString("\n") { "a,b,c" }
        val read = DelimitedFile.parse(text, ',', maxCells = 7)

        assertEquals(3, read.rows.size)
        assertTrue(read.rows.all { it.size == 3 })
        assertTrue(read.truncated)
    }

    @Test
    fun `a file that ends exactly at the limit is not truncated`() {
        assertFalse(DelimitedFile.parse("a,b\nc,d\n", ',', maxCells = 4).truncated)
        assertFalse(DelimitedFile.parse("a,b\nc,d\n\n  \n", ',', maxCells = 4).truncated)
        assertEquals(2, DelimitedFile.parse("a,b\nc,d\n", ',', maxCells = 4).rows.size)
    }

    @Test
    fun `a file under every limit is not truncated`() {
        assertFalse(DelimitedFile.parse("a,b\nc,d", ',').truncated)
    }

    @Test
    fun `a row of too many columns keeps the first ones`() {
        val line = (1..DelimitedFile.MAX_COLUMNS + 50).joinToString(",")
        val read = DelimitedFile.parse(line + "\nx,y", ',')

        assertEquals(DelimitedFile.MAX_COLUMNS, read.rows[0].size)
        assertEquals("1", read.rows[0][0])
        assertEquals(listOf("x", "y"), read.rows[1])
        assertTrue(read.truncated)
    }

    @Test
    fun `a cell past its limit is cut with an ellipsis, so a stray quote cannot swallow the file`() {
        val swallowed = "\"" + "x".repeat(DelimitedFile.MAX_CELL_CHARS * 3) + ",not a cell\nneither"
        val read = DelimitedFile.parse("a,$swallowed", ',')

        assertEquals(2, read.rows[0].size)
        assertEquals(DelimitedFile.MAX_CELL_CHARS + 1, read.rows[0][1].length)
        assertTrue(read.rows[0][1].endsWith("…"))
        assertTrue(read.truncated)
    }

    @Test
    fun `a cell exactly at its limit is whole`() {
        val read = DelimitedFile.parse("x".repeat(DelimitedFile.MAX_CELL_CHARS), ',')

        assertEquals(DelimitedFile.MAX_CELL_CHARS, read.rows[0][0].length)
        assertFalse(read.truncated)
    }

    @Test
    fun `text cut by a size limit loses its half-read last row`() {
        val read = DelimitedFile.parse("a,b\nc,d\ne,", ',', cut = true)

        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), read.rows)
        assertTrue(read.truncated)
    }

    @Test
    fun `a cut file that is a single row keeps it`() {
        val read = DelimitedFile.parse("a,b,c", ',', cut = true)

        assertEquals(listOf(listOf("a", "b", "c")), read.rows)
        assertTrue(read.truncated)
    }

    // --- the table ----------------------------------------------------------------------------

    @Test
    fun `rows become one table with the first row as a bold header`() {
        val blocks = DelimitedFile.toBlocks(listOf(listOf("name", ""), listOf("Ann", "42")))
        val table = blocks.single() as OfficeBlock.Table

        assertEquals(2, table.rows.size)
        assertEquals(listOf(0, 1), table.rows[0].map { it.column })
        assertEquals(listOf(TextSpan(0, 4, CharFormat(bold = true))), table.rows[0][0].spans)
        // Nothing to make bold in an empty header cell, and the body is plain.
        assertEquals(emptyList<TextSpan>(), table.rows[0][1].spans)
        assertEquals(emptyList<TextSpan>(), table.rows[1][0].spans)
        assertEquals("42", table.rows[1][1].text)
        assertTrue(table.rows.flatten().all { it.colspan == 1 && it.rowspan == 1 && it.background == null })
    }

    @Test
    fun `a header cell of several lines is bold on each of them`() {
        val table = DelimitedFile.toBlocks(listOf(listOf("two\nlines"))).single() as OfficeBlock.Table
        val cell = table.rows[0][0]
        val lines = splitLines(cell.text, cell.spans)

        assertEquals(listOf("two", "lines"), lines.map { it.text })
        assertEquals(listOf(listOf(TextSpan(0, 3, CharFormat(bold = true))), listOf(TextSpan(0, 5, CharFormat(bold = true)))), lines.map { it.spans })
    }

    @Test
    fun `a ragged row keeps each cell on its own column`() {
        val table = DelimitedFile.toBlocks(listOf(listOf("a", "b", "c"), listOf("1"))).single() as OfficeBlock.Table

        assertEquals(listOf(0), table.rows[1].map { it.column })
    }

    @Test
    fun `no rows give no blocks`() {
        assertEquals(emptyList<OfficeBlock>(), DelimitedFile.toBlocks(emptyList()))
    }

    @Test
    fun `a realistic file survives from text to table`() {
        val text = "\uFEFFid;name;note\r\n1;\"Ann\";\"likes \"\"quotes\"\"\r\nand lines\"\r\n2;Bo;\r\n"
        val delimiter = DelimitedFile.delimiterFor("people.csv", text)
        val table = DelimitedFile.toBlocks(DelimitedFile.parse(text, delimiter).rows).single() as OfficeBlock.Table

        assertEquals(';', delimiter)
        assertEquals(3, table.rows.size)
        assertEquals(listOf("id", "name", "note"), table.rows[0].map { it.text })
        assertEquals("likes \"quotes\"\nand lines", table.rows[1][2].text)
        assertEquals(listOf("2", "Bo", ""), table.rows[2].map { it.text })
    }
}
