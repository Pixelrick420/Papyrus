package com.papyrus.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BookmarkRulesTest {

    private fun page(unit: Int, fraction: Float = 0f) = ReadingLocation(LocationKind.PAGE, unit, fraction)
    private fun chunk(unit: Int, fraction: Float = 0f) = ReadingLocation(LocationKind.CHUNK, unit, fraction)
    private fun block(unit: Int, fraction: Float = 0f) = ReadingLocation(LocationKind.BLOCK, unit, fraction)
    private fun char(offset: Int) = ReadingLocation(LocationKind.CHAR, offset)

    private fun row(
        id: Long,
        location: ReadingLocation,
        label: String = "b$id",
        createdAt: Long = id,
    ) = BookmarkEntity(
        id = id,
        documentId = 1,
        locationKind = location.kind.code,
        locationUnit = location.unit,
        locationFraction = location.fraction,
        label = label,
        createdAt = createdAt,
    )

    @Test
    fun `any two places on one pdf page are the same bookmark`() {
        assertTrue(page(3, 0.1f).isSameBookmarkSpot(page(3, 0.9f)))
        assertFalse(page(3).isSameBookmarkSpot(page(4)))
    }

    @Test
    fun `text chunks tolerate two lines`() {
        assertTrue(chunk(5, 0.50f).isSameBookmarkSpot(chunk(5, 0.54f)))
        assertFalse(chunk(5, 0.50f).isSameBookmarkSpot(chunk(5, 0.60f)))
        // The fraction is only meaningful inside one chunk.
        assertFalse(chunk(5, 0.99f).isSameBookmarkSpot(chunk(6, 0.0f)))
    }

    @Test
    fun `office blocks tolerate a tenth of a block`() {
        assertTrue(block(12, 0.30f).isSameBookmarkSpot(block(12, 0.38f)))
        assertFalse(block(12, 0.30f).isSameBookmarkSpot(block(12, 0.50f)))
        assertFalse(block(12).isSameBookmarkSpot(block(13)))
    }

    @Test
    fun `markdown offsets tolerate a couple of rows of text`() {
        assertTrue(char(1000).isSameBookmarkSpot(char(1080)))
        assertFalse(char(1000).isSameBookmarkSpot(char(1200)))
        assertTrue(char(Int.MAX_VALUE).isSameBookmarkSpot(char(Int.MAX_VALUE - 5)))
    }

    @Test
    fun `places of different kinds are never the same`() {
        assertFalse(page(3).isSameBookmarkSpot(block(3)))
        assertFalse(chunk(0).isSameBookmarkSpot(char(0)))
    }

    @Test
    fun `a new spot is added`() {
        assertEquals(BookmarkDecision.ADD, decideBookmark(listOf(page(1), page(2)), page(5)))
        assertEquals(BookmarkDecision.ADD, decideBookmark(emptyList(), page(0)))
    }

    @Test
    fun `a bookmarked spot is reported instead of duplicated`() {
        assertEquals(BookmarkDecision.ALREADY_BOOKMARKED, decideBookmark(listOf(page(1), page(2)), page(2, 0.7f)))
    }

    @Test
    fun `the cap refuses the first bookmark past it`() {
        val full = (0 until MAX_BOOKMARKS_PER_DOCUMENT).map { page(it) }
        assertEquals(200, full.size)
        assertEquals(BookmarkDecision.LIMIT_REACHED, decideBookmark(full, page(500)))
        assertEquals(BookmarkDecision.ADD, decideBookmark(full.drop(1), page(500)))
    }

    @Test
    fun `a full list still says already bookmarked for a spot it has`() {
        val full = (0 until MAX_BOOKMARKS_PER_DOCUMENT).map { page(it) }
        assertEquals(BookmarkDecision.ALREADY_BOOKMARKED, decideBookmark(full, page(7)))
    }

    @Test
    fun `the limit is a parameter`() {
        assertEquals(BookmarkDecision.LIMIT_REACHED, decideBookmark(listOf(page(0)), page(1), limit = 1))
    }

    @Test
    fun `a pdf place reads as a one based page`() {
        assertEquals(LocationSummary(SummaryKind.PAGE, 1), page(0).summary(10))
        assertEquals(LocationSummary(SummaryKind.PAGE, 12), page(11, 0.4f).summary(40))
    }

    @Test
    fun `a page past the end reads as the last page`() {
        assertEquals(LocationSummary(SummaryKind.PAGE, 10), page(99).summary(10))
    }

    @Test
    fun `a text place reads as the line it is on`() {
        assertEquals(LocationSummary(SummaryKind.LINE, 1), chunk(0).summary(100))
        // Chunk 8 starts at line 321; half way down is line 341.
        assertEquals(LocationSummary(SummaryKind.LINE, 341), chunk(8, 0.5f).summary(100))
        // A full fraction stays on the chunk's last line instead of reading as the next chunk's first.
        assertEquals(LocationSummary(SummaryKind.LINE, 40), chunk(0, 1f).summary(100))
    }

    @Test
    fun `a text place past the end reads as a line of the last chunk`() {
        val line = chunk(500, 0.5f).summary(3)
        assertEquals(SummaryKind.LINE, line.kind)
        // Clamping drops the fraction: the nearest real place is the top of the last chunk.
        assertEquals(2 * 40 + 1, line.number)
    }

    @Test
    fun `block and markdown places read as a percentage`() {
        assertEquals(LocationSummary(SummaryKind.PERCENT, 0), block(0).summary(200))
        assertEquals(LocationSummary(SummaryKind.PERCENT, 45), block(90).summary(200))
        assertEquals(LocationSummary(SummaryKind.PERCENT, 50), char(500).summary(1000))
    }

    @Test
    fun `an empty document reads as zero percent`() {
        assertEquals(LocationSummary(SummaryKind.PERCENT, 0), block(5).summary(0))
        assertEquals(LocationSummary(SummaryKind.PERCENT, 0), char(5).summary(0))
    }

    @Test
    fun `a NaN fraction cannot break a summary`() {
        assertEquals(LocationSummary(SummaryKind.LINE, 41), chunk(1, Float.NaN).summary(10))
    }

    @Test
    fun `a typed label is one trimmed line`() {
        assertEquals("Chapter two", cleanBookmarkLabel("  Chapter\n  two \t", "Page 3"))
    }

    @Test
    fun `a blank label falls back to the positional default`() {
        assertEquals("Page 3", cleanBookmarkLabel("   \n ", "Page 3"))
        assertEquals("Page 3", cleanBookmarkLabel("", "Page 3"))
    }

    @Test
    fun `a long label is cut`() {
        val cut = cleanBookmarkLabel("x".repeat(500), "Page 3")
        assertEquals(MAX_BOOKMARK_LABEL_CHARS, cut.length)
    }

    @Test
    fun `bookmarks come back in document order`() {
        val rows = listOf(
            row(1, page(9)),
            row(2, page(2, 0.5f)),
            row(3, page(2, 0.1f)),
            row(4, page(2, 0.1f), createdAt = 0),
        )
        assertEquals(listOf(4L, 3L, 2L, 1L), rows.forFormat(DocumentFormat.PDF).map { it.id })
    }

    @Test
    fun `a row of another kind than the format reads in is left out`() {
        val rows = listOf(row(1, page(1)), row(2, chunk(1)))
        assertEquals(listOf(1L), rows.forFormat(DocumentFormat.PDF).map { it.id })
        assertEquals(listOf(2L), rows.forFormat(DocumentFormat.TEXT).map { it.id })
    }

    @Test
    fun `a row with an unknown kind code is ignored`() {
        val unknown = row(1, page(1)).copy(locationKind = 99)
        assertNull(unknown.toLocation())
        assertEquals(emptyList<Bookmark>(), listOf(unknown).forFormat(DocumentFormat.PDF))
    }

    @Test
    fun `epub bookmarks are block places like Word's`() {
        val rows = listOf(row(1, block(40, 0.5f)), row(2, block(3)))
        assertEquals(listOf(2L, 1L), rows.forFormat(DocumentFormat.EPUB).map { it.id })
    }

    @Test
    fun `a format with no viewer has no bookmarks`() {
        assertEquals(emptyList<Bookmark>(), listOf(row(1, page(1))).forFormat(DocumentFormat.UNKNOWN))
    }

    // --- Label truncation ---

    @Test
    fun `a label of exactly the maximum is kept whole and one more is cut`() {
        val exact = "x".repeat(MAX_BOOKMARK_LABEL_CHARS)

        assertEquals(exact, cleanBookmarkLabel(exact, "Page 3"))
        assertEquals(exact, cleanBookmarkLabel(exact + "y", "Page 3"))
        assertEquals(MAX_BOOKMARK_LABEL_CHARS, cleanBookmarkLabel("x".repeat(10_000), "Page 3").length)
    }

    @Test
    fun `the cut keeps the start of the label, not the end`() {
        val label = "start-" + "m".repeat(200) + "-end"

        val cut = cleanBookmarkLabel(label, "Page 3")

        assertTrue(cut.startsWith("start-"))
        assertFalse(cut.endsWith("-end"))
    }

    @Test
    fun `a cut that lands on a space does not leave it at the end`() {
        // 79 letters, a space as the 80th character, then more text.
        val cut = cleanBookmarkLabel("x".repeat(MAX_BOOKMARK_LABEL_CHARS - 1) + " tail", "Page 3")

        assertEquals("x".repeat(MAX_BOOKMARK_LABEL_CHARS - 1), cut)
    }

    @Test
    fun `leading whitespace and runs of it do not count against the length`() {
        val label = "   " + "word  ".repeat(10)

        assertEquals("word ".repeat(9) + "word", cleanBookmarkLabel(label, "Page 3"))
        val long = " ".repeat(500) + "x".repeat(MAX_BOOKMARK_LABEL_CHARS)
        assertEquals("x".repeat(MAX_BOOKMARK_LABEL_CHARS), cleanBookmarkLabel(long, "Page 3"))
    }

    @Test
    fun `every kind of line break becomes one space`() {
        assertEquals("a b c d", cleanBookmarkLabel("a\nb\r\nc\rd", "Page 3"))
        assertEquals("a b", cleanBookmarkLabel("a\t\t \n b", "Page 3"))
    }

    @Test
    fun `a label cleaned twice is the same as cleaned once`() {
        val random = Random(11)
        val pieces = listOf("a", "bb", " ", "  ", "\n", "\t", "é", "日本", "x".repeat(40))
        repeat(300) {
            val raw = buildString { repeat(random.nextInt(0, 12)) { append(pieces[random.nextInt(pieces.size)]) } }
            val once = cleanBookmarkLabel(raw, "Page 3")

            assertEquals(raw, once, cleanBookmarkLabel(once, "Page 3"))
            assertTrue(once.length <= MAX_BOOKMARK_LABEL_CHARS)
            assertEquals(once, once.trim())
        }
    }

    @Test
    fun `the fallback is used as given, and is not cut or cleaned`() {
        assertEquals("Page 3", cleanBookmarkLabel("\n", "Page 3"))
        assertEquals("a  b", cleanBookmarkLabel("", "a  b"))
    }

    // --- Duplicate detection ---

    @Test
    fun `duplicate detection is symmetric`() {
        val random = Random(3)
        fun place(): ReadingLocation {
            val kind = LocationKind.entries[random.nextInt(LocationKind.entries.size)]
            return ReadingLocation(kind, random.nextInt(0, 6) * 40, random.nextInt(0, 21) / 20f)
        }
        repeat(2_000) {
            val a = place()
            val b = place()

            assertEquals("$a vs $b", a.isSameBookmarkSpot(b), b.isSameBookmarkSpot(a))
            assertTrue("$a is not its own duplicate", a.isSameBookmarkSpot(a))
        }
    }

    @Test
    fun `a chunk is a duplicate within two lines and not beyond`() {
        assertTrue(chunk(5, 0.50f).isSameBookmarkSpot(chunk(5, 0.54f)))
        assertTrue(chunk(5, 0.50f).isSameBookmarkSpot(chunk(5, 0.46f)))
        assertFalse(chunk(5, 0.50f).isSameBookmarkSpot(chunk(5, 0.56f)))
        assertFalse(chunk(5, 0.50f).isSameBookmarkSpot(chunk(5, 0.44f)))
    }

    @Test
    fun `a block is a duplicate within a tenth and not beyond`() {
        assertTrue(block(2, 0.50f).isSameBookmarkSpot(block(2, 0.59f)))
        assertTrue(block(2, 0.50f).isSameBookmarkSpot(block(2, 0.41f)))
        assertFalse(block(2, 0.50f).isSameBookmarkSpot(block(2, 0.61f)))
        assertFalse(block(2, 0.50f).isSameBookmarkSpot(block(2, 0.39f)))
    }

    @Test
    fun `a markdown offset is a duplicate at exactly the tolerance and not one past it`() {
        assertTrue(char(1_000).isSameBookmarkSpot(char(1_100)))
        assertTrue(char(1_000).isSameBookmarkSpot(char(900)))
        assertFalse(char(1_000).isSameBookmarkSpot(char(1_101)))
        assertFalse(char(1_000).isSameBookmarkSpot(char(899)))
        assertTrue(char(0).isSameBookmarkSpot(char(100)))
    }

    @Test
    fun `offsets at the ends of the int range do not overflow into a false match`() {
        assertFalse(char(Int.MIN_VALUE).isSameBookmarkSpot(char(Int.MAX_VALUE)))
        assertFalse(char(0).isSameBookmarkSpot(char(Int.MAX_VALUE)))
        assertTrue(char(Int.MIN_VALUE).isSameBookmarkSpot(char(Int.MIN_VALUE + 100)))
    }

    @Test
    fun `the fraction of a place is ignored for a page and for an offset`() {
        assertTrue(page(4, 0.0f).isSameBookmarkSpot(page(4, 1.0f)))
        assertTrue(ReadingLocation(LocationKind.CHAR, 500, 0.0f).isSameBookmarkSpot(ReadingLocation(LocationKind.CHAR, 500, 1f)))
    }

    @Test
    fun `a spot of another kind never blocks a new one, even on the same numbers`() {
        val existing = listOf(chunk(3, 0.5f), block(3, 0.5f), char(3))

        assertEquals(BookmarkDecision.ADD, decideBookmark(existing, page(3)))
    }

    @Test
    fun `any one match among many is enough`() {
        val existing = (0 until 50).map { page(it) } + chunk(7, 0.5f)

        assertEquals(BookmarkDecision.ALREADY_BOOKMARKED, decideBookmark(existing, chunk(7, 0.52f)))
        assertEquals(BookmarkDecision.ADD, decideBookmark(existing, chunk(8, 0.52f)))
    }

    // --- The per-document cap ---

    @Test
    fun `the cap is exact at one below, at, and above the limit`() {
        val below = (0 until MAX_BOOKMARKS_PER_DOCUMENT - 1).map { page(it) }
        val at = below + page(MAX_BOOKMARKS_PER_DOCUMENT)
        val above = at + (1..5).map { page(1_000 + it) }
        val candidate = page(9_999)

        assertEquals(BookmarkDecision.ADD, decideBookmark(below, candidate))
        assertEquals(BookmarkDecision.LIMIT_REACHED, decideBookmark(at, candidate))
        assertEquals(BookmarkDecision.LIMIT_REACHED, decideBookmark(above, candidate))
    }

    @Test
    fun `the cap is two hundred`() {
        assertEquals(200, MAX_BOOKMARKS_PER_DOCUMENT)
        assertEquals(80, MAX_BOOKMARK_LABEL_CHARS)
    }

    @Test
    fun `a duplicate is reported before the cap at every size past it`() {
        for (extra in 0..3) {
            val full = (0 until MAX_BOOKMARKS_PER_DOCUMENT + extra).map { page(it) }

            assertEquals(BookmarkDecision.ALREADY_BOOKMARKED, decideBookmark(full, page(0, 0.9f)))
        }
    }

    @Test
    fun `a limit of zero refuses everything that is new`() {
        assertEquals(BookmarkDecision.LIMIT_REACHED, decideBookmark(emptyList(), page(0), limit = 0))
    }

    @Test
    fun `every spot a limited list holds can be told apart from every spot it lacks`() {
        val held = (0 until 5).map { page(it) }

        for (unit in 0 until 5) assertEquals(BookmarkDecision.ALREADY_BOOKMARKED, decideBookmark(held, page(unit), limit = 5))
        for (unit in 5 until 10) assertEquals(BookmarkDecision.LIMIT_REACHED, decideBookmark(held, page(unit), limit = 5))
    }
}
