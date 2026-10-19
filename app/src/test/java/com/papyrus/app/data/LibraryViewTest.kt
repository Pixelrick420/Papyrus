package com.papyrus.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class LibraryViewTest {

    private fun doc(
        id: Long,
        title: String = "doc$id",
        format: DocumentFormat = DocumentFormat.PDF,
        size: Long = 1_000,
        created: Long = id,
        opened: Long = id,
        favourite: Boolean = false,
    ) = DocumentEntity(
        id = id,
        uri = "content://x/$id",
        title = title,
        format = format,
        sizeBytes = size,
        createdAt = created,
        lastOpenedAt = opened,
        isFavourite = favourite,
    )

    private fun ids(list: List<DocumentEntity>) = list.map { it.id }

    private fun view(
        docs: List<DocumentEntity>,
        sort: LibrarySort,
        query: String = "",
        filter: LibraryFilter = LibraryFilter(),
        locale: Locale = Locale.ENGLISH,
    ) = libraryView(docs, query, filter, sort, locale)

    // --- sort orders -----------------------------------------------------------------------------

    @Test
    fun `last opened puts the most recent first`() {
        val docs = listOf(doc(1, opened = 10), doc(2, opened = 30), doc(3, opened = 20))
        assertEquals(listOf(2L, 3L, 1L), ids(view(docs, LibrarySort.LAST_OPENED)))
    }

    @Test
    fun `date added puts the newest addition first and ignores when it was opened`() {
        val docs = listOf(doc(1, created = 300, opened = 1), doc(2, created = 100, opened = 99), doc(3, created = 200, opened = 50))
        assertEquals(listOf(1L, 3L, 2L), ids(view(docs, LibrarySort.DATE_ADDED)))
    }

    @Test
    fun `size puts the largest first`() {
        val docs = listOf(doc(1, size = 10), doc(2, size = 3_000_000_000), doc(3, size = 500))
        assertEquals(listOf(2L, 3L, 1L), ids(view(docs, LibrarySort.SIZE)))
    }

    @Test
    fun `name ascending and descending are mirror images apart from ties`() {
        val docs = listOf(doc(1, "banana.pdf"), doc(2, "apple.pdf"), doc(3, "cherry.pdf"))
        assertEquals(listOf(2L, 1L, 3L), ids(view(docs, LibrarySort.NAME_ASC)))
        assertEquals(listOf(3L, 1L, 2L), ids(view(docs, LibrarySort.NAME_DESC)))
    }

    // --- natural, case-insensitive, locale-aware names --------------------------------------------

    @Test
    fun `numbers inside names compare as numbers`() {
        val docs = listOf(doc(1, "file10.pdf"), doc(2, "file2.pdf"), doc(3, "file1.pdf"), doc(4, "file100.pdf"))
        assertEquals(listOf(3L, 2L, 1L, 4L), ids(view(docs, LibrarySort.NAME_ASC)))
        assertEquals(listOf(4L, 1L, 2L, 3L), ids(view(docs, LibrarySort.NAME_DESC)))
    }

    @Test
    fun `several numbers in a name are each compared as numbers`() {
        val docs = listOf(doc(1, "v1.10.pdf"), doc(2, "v1.9.pdf"), doc(3, "v1.2.pdf"), doc(4, "v2.1.pdf"))
        assertEquals(listOf(3L, 2L, 1L, 4L), ids(view(docs, LibrarySort.NAME_ASC)))
    }

    @Test
    fun `a number longer than any long still compares`() {
        val huge = "9".repeat(60)
        val docs = listOf(doc(1, "x${huge}1"), doc(2, "x$huge"), doc(3, "x5"))
        assertEquals(listOf(3L, 2L, 1L), ids(view(docs, LibrarySort.NAME_ASC)))
    }

    @Test
    fun `case does not decide the order`() {
        val docs = listOf(doc(1, "beta.txt"), doc(2, "Alpha.txt"), doc(3, "alpine.txt"), doc(4, "Gamma.txt"))
        assertEquals(listOf(2L, 3L, 1L, 4L), ids(view(docs, LibrarySort.NAME_ASC)))
    }

    @Test
    fun `digits come before letters`() {
        val docs = listOf(doc(1, "alpha"), doc(2, "7zip"), doc(3, "Beta"))
        assertEquals(listOf(2L, 1L, 3L), ids(view(docs, LibrarySort.NAME_ASC)))
    }

    @Test
    fun `a name that is a prefix of another comes first`() {
        val docs = listOf(doc(1, "reports"), doc(2, "report2"), doc(3, "report"))
        // The bare name, then the digit that follows it, then the letter: digits sort before letters.
        assertEquals(listOf(3L, 2L, 1L), ids(view(docs, LibrarySort.NAME_ASC)))
    }

    @Test
    fun `letters follow the locale's alphabet`() {
        val docs = listOf(doc(1, "Åsa.txt"), doc(2, "Zoe.txt"), doc(3, "Bo.txt"))
        // English files an A with a ring beside the other As; Swedish puts it after Z.
        assertEquals(listOf(1L, 3L, 2L), ids(view(docs, LibrarySort.NAME_ASC, locale = Locale.ENGLISH)))
        assertEquals(listOf(3L, 2L, 1L), ids(view(docs, LibrarySort.NAME_ASC, locale = Locale.forLanguageTag("sv-SE"))))
    }

    @Test
    fun `an accent breaks a tie between otherwise equal words but never outranks the letters`() {
        val docs = listOf(doc(1, "resume.pdf"), doc(2, "résumé.pdf"), doc(3, "rest.pdf"))
        // rest < resume because t comes before u; the accented spelling sits right after the plain one.
        assertEquals(listOf(3L, 1L, 2L), ids(view(docs, LibrarySort.NAME_ASC)))
    }

    // --- ties -----------------------------------------------------------------------------------

    @Test
    fun `equal names fall back to most recently opened and then id`() {
        val docs = listOf(
            doc(1, "same.pdf", opened = 10),
            doc(2, "SAME.pdf", opened = 30),
            doc(3, "same.pdf", opened = 30),
            doc(4, "same.pdf", opened = 20),
        )
        val expected = listOf(2L, 3L, 4L, 1L)
        assertEquals(expected, ids(view(docs, LibrarySort.NAME_ASC)))
        // Descending reverses the names only; the tie-break is the same, so a list of equals never flips.
        assertEquals(expected, ids(view(docs, LibrarySort.NAME_DESC)))
    }

    @Test
    fun `names that differ only in leading zeros are a tie`() {
        val docs = listOf(doc(1, "file02.pdf", opened = 1), doc(2, "file2.pdf", opened = 2))
        assertEquals(listOf(2L, 1L), ids(view(docs, LibrarySort.NAME_ASC)))
    }

    @Test
    fun `equal sizes and dates fall back to opened then id`() {
        val bySize = listOf(doc(1, size = 5, opened = 1), doc(2, size = 5, opened = 9), doc(3, size = 5, opened = 9))
        assertEquals(listOf(2L, 3L, 1L), ids(view(bySize, LibrarySort.SIZE)))
        val byDate = listOf(doc(1, created = 5, opened = 1), doc(2, created = 5, opened = 9), doc(3, created = 5, opened = 9))
        assertEquals(listOf(2L, 3L, 1L), ids(view(byDate, LibrarySort.DATE_ADDED)))
    }

    @Test
    fun `last opened ties break by id`() {
        val docs = listOf(doc(3, opened = 7), doc(1, opened = 7), doc(2, opened = 7))
        assertEquals(listOf(1L, 2L, 3L), ids(view(docs, LibrarySort.LAST_OPENED)))
    }

    @Test
    fun `the same input always gives the same order`() {
        val docs = (1L..40L).map { doc(it, "n${it % 5}", opened = it % 3, size = it % 4) }
        for (sort in LibrarySort.entries) {
            assertEquals(ids(view(docs, sort)), ids(view(docs.reversed(), sort)))
        }
    }

    @Test
    fun `sorting never changes what is in the list`() {
        val docs = (1L..25L).map { doc(it, "t${it * 7 % 11}") }
        for (sort in LibrarySort.entries) {
            assertEquals(docs.map { it.id }.toSet(), view(docs, sort).map { it.id }.toSet())
        }
    }

    // --- format groups ----------------------------------------------------------------------------

    @Test
    fun `old and new Word files are one group`() {
        assertEquals(FormatGroup.WORD, FormatGroup.of(DocumentFormat.DOC))
        assertEquals(FormatGroup.WORD, FormatGroup.of(DocumentFormat.DOCX))
    }

    @Test
    fun `epub is its own chip, placed after odt`() {
        val docs = listOf(
            doc(1, format = DocumentFormat.MARKDOWN),
            doc(2, format = DocumentFormat.EPUB),
            doc(3, format = DocumentFormat.ODT),
        )
        assertEquals(listOf(FormatGroup.ODT, FormatGroup.EPUB, FormatGroup.MARKDOWN), availableGroups(docs))
        val filter = LibraryFilter().toggled(FormatGroup.EPUB)
        assertEquals(listOf(2L), ids(view(docs, LibrarySort.LAST_OPENED, filter = filter)))
    }

    @Test
    fun `every format with a viewer has a group and unknown has none`() {
        for (format in DocumentFormat.entries) {
            if (format == DocumentFormat.UNKNOWN) {
                assertEquals(null, FormatGroup.of(format))
            } else {
                assertTrue("$format", FormatGroup.of(format) != null)
            }
        }
    }

    @Test
    fun `chips are built from the formats the library has, in a fixed order`() {
        val docs = listOf(
            doc(1, format = DocumentFormat.CODE),
            doc(2, format = DocumentFormat.DOC),
            doc(3, format = DocumentFormat.PDF),
            doc(4, format = DocumentFormat.DOCX),
            doc(5, format = DocumentFormat.UNKNOWN),
        )
        assertEquals(listOf(FormatGroup.PDF, FormatGroup.WORD, FormatGroup.CODE), availableGroups(docs))
        assertEquals(emptyList<FormatGroup>(), availableGroups(emptyList()))
    }

    // --- filter and search ----------------------------------------------------------------------

    private val library = listOf(
        doc(1, "Annual report.pdf", DocumentFormat.PDF, favourite = true),
        doc(2, "Report draft.docx", DocumentFormat.DOCX),
        doc(3, "Old report.doc", DocumentFormat.DOC, favourite = true),
        doc(4, "notes.md", DocumentFormat.MARKDOWN),
        doc(5, "report.py", DocumentFormat.CODE, favourite = true),
        doc(6, "mystery", DocumentFormat.UNKNOWN),
    )

    @Test
    fun `no filter and no query shows everything`() {
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L), view(library, LibrarySort.LAST_OPENED).map { it.id }.toSet())
    }

    @Test
    fun `selected formats widen the list`() {
        val filter = LibraryFilter().toggled(FormatGroup.PDF).toggled(FormatGroup.MARKDOWN)
        assertEquals(setOf(1L, 4L), view(library, LibrarySort.LAST_OPENED, filter = filter).map { it.id }.toSet())
    }

    @Test
    fun `the Word chip shows both Word generations`() {
        val filter = LibraryFilter().toggled(FormatGroup.WORD)
        assertEquals(setOf(2L, 3L), view(library, LibrarySort.LAST_OPENED, filter = filter).map { it.id }.toSet())
    }

    @Test
    fun `a format filter hides files with no group`() {
        val filter = LibraryFilter().toggled(FormatGroup.PDF)
        assertFalse(6L in view(library, LibrarySort.LAST_OPENED, filter = filter).map { it.id })
    }

    @Test
    fun `favourites narrow what the formats let through`() {
        val onlyFavourites = LibraryFilter().withFavouritesOnly(true)
        assertEquals(setOf(1L, 3L, 5L), view(library, LibrarySort.LAST_OPENED, filter = onlyFavourites).map { it.id }.toSet())
        val favouriteWord = onlyFavourites.toggled(FormatGroup.WORD)
        assertEquals(setOf(3L), view(library, LibrarySort.LAST_OPENED, filter = favouriteWord).map { it.id }.toSet())
    }

    @Test
    fun `search and filter compose`() {
        val filter = LibraryFilter().toggled(FormatGroup.WORD).toggled(FormatGroup.PDF)
        val shown = view(library, LibrarySort.NAME_ASC, query = "  REPORT ", filter = filter)
        assertEquals(listOf(1L, 3L, 2L), ids(shown))
        val none = view(library, LibrarySort.NAME_ASC, query = "notes", filter = filter)
        assertEquals(emptyList<Long>(), ids(none))
    }

    @Test
    fun `a blank query is no query`() {
        assertEquals(6, view(library, LibrarySort.LAST_OPENED, query = "   ").size)
    }

    @Test
    fun `toggling a chip twice is no chip and the filter reports when it is on`() {
        val filter = LibraryFilter()
        assertFalse(filter.isActive)
        val on = filter.toggled(FormatGroup.ODT)
        assertTrue(on.isActive)
        assertEquals(filter, on.toggled(FormatGroup.ODT))
        assertTrue(filter.withFavouritesOnly(true).isActive)
        assertFalse(filter.withFavouritesOnly(true).withFavouritesOnly(false).isActive)
    }

    // --- favourites ----------------------------------------------------------------------------

    @Test
    fun `opening a document again keeps what the reader owns`() {
        val stored = doc(7, "old name.pdf", size = 10, created = 111, opened = 222, favourite = true)
        val fresh = doc(0, "new name.pdf", DocumentFormat.PDF, size = 99, created = 999, opened = 333, favourite = false)
            .copy(uri = "content://elsewhere")
        val after = stored.reopenedWith(fresh)
        assertEquals(7L, after.id)
        assertEquals("content://x/7", after.uri)
        assertEquals(111L, after.createdAt)
        assertTrue(after.isFavourite)
        assertEquals("new name.pdf", after.title)
        assertEquals(99L, after.sizeBytes)
        assertEquals(333L, after.lastOpenedAt)
    }

    @Test
    fun `a favourite flag set on a row shows up in the favourites filter`() {
        val before = listOf(doc(1), doc(2))
        val filter = LibraryFilter().withFavouritesOnly(true)
        assertEquals(emptyList<Long>(), ids(view(before, LibrarySort.LAST_OPENED, filter = filter)))
        val after = before.map { if (it.id == 2L) it.copy(isFavourite = true) else it }
        assertEquals(listOf(2L), ids(view(after, LibrarySort.LAST_OPENED, filter = filter)))
    }
}
