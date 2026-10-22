package com.papyrus.app.data

import java.text.Collator
import java.util.Locale

/** How Home orders the library. The choice is kept in [SettingsRepository]. */
enum class LibrarySort {
    /** Most recently opened first: what the list has always shown. */
    LAST_OPENED,
    NAME_ASC,
    NAME_DESC,

    /** Newest addition first. */
    DATE_ADDED,

    /** Largest first. */
    SIZE,
}

/**
 * The formats a reader tells apart, not the ones the code does: Word's two generations are one chip,
 * and a file with no viewer ([DocumentFormat.UNKNOWN]) belongs to none, so a format filter hides it.
 */
enum class FormatGroup {
    PDF,
    WORD,
    ODT,
    EPUB,
    MARKDOWN,
    TEXT,
    CSV,

    /** The tab-separated half of [DocumentFormat.CSV]; the group follows the file name, not the format. */
    TSV,
    CODE,
    ;

    companion object {
        fun of(document: DocumentEntity): FormatGroup? = when (document.format) {
            DocumentFormat.PDF -> PDF
            DocumentFormat.DOC, DocumentFormat.DOCX -> WORD
            DocumentFormat.ODT -> ODT
            DocumentFormat.EPUB -> EPUB
            DocumentFormat.MARKDOWN -> MARKDOWN
            DocumentFormat.TEXT -> TEXT
            // One format covers both extensions, so only the name can tell a tab-separated file apart.
            DocumentFormat.CSV -> if (extensionOf(document.title) == "tsv") TSV else CSV
            DocumentFormat.CODE -> CODE
            DocumentFormat.UNKNOWN -> null
        }
    }
}

/**
 * The name's extension, lower-cased after the last dot, or empty if there is none.
 */
private fun extensionOf(title: String): String {
    val name = title.lowercase()
    return if (name.contains('.')) name.substringAfterLast('.') else ""
}

/**
 * The filters Home has selected. Format groups widen (PDF and Word); Starred narrows to starred
 * documents only.
 */
data class LibraryFilter(
    val groups: Set<FormatGroup> = emptySet(),
    val starredOnly: Boolean = false,
) {
    /** How many filters are on, so the badge and the "is anything filtered?" flag cannot disagree. */
    val activeCount: Int get() = groups.size + if (starredOnly) 1 else 0

    val isActive: Boolean get() = activeCount > 0

    fun toggled(group: FormatGroup): LibraryFilter =
        copy(groups = if (group in groups) groups - group else groups + group)

    fun withStarredOnly(value: Boolean): LibraryFilter = copy(starredOnly = value)

    fun matches(document: DocumentEntity): Boolean {
        if (starredOnly && !document.isStarred) return false
        if (groups.isEmpty()) return true
        val group = FormatGroup.of(document) ?: return false
        return group in groups
    }
}

/** The groups the library actually has, in chip order, so a chip never leads to an empty list. */
fun availableGroups(documents: List<DocumentEntity>): List<FormatGroup> {
    val present = documents.mapNotNullTo(HashSet()) { FormatGroup.of(it) }
    return FormatGroup.entries.filter { it in present }
}

/**
 * What Home shows: [documents] kept by the title [query] (case-insensitive, as before) and by [filter],
 * then put in [sort] order. Pure, so every combination is unit-tested; sorting in memory is fine at library
 * sizes and keeps the one list query as it was.
 */
fun libraryView(
    documents: List<DocumentEntity>,
    query: String,
    filter: LibraryFilter,
    sort: LibrarySort,
    locale: Locale = Locale.getDefault(),
): List<DocumentEntity> {
    val needle = query.trim()
    val kept = documents.filter { filter.matches(it) && (needle.isEmpty() || it.title.contains(needle, ignoreCase = true)) }
    return kept.sortedWith(comparatorFor(sort, locale))
}

/**
 * Ties never fall to the database's whim: whatever the order asks for, equal documents come most recently
 * opened first and then by id, so the list is the same on every emission and `animateItem` only moves a
 * row when something about it changed.
 */
internal fun comparatorFor(sort: LibrarySort, locale: Locale = Locale.getDefault()): Comparator<DocumentEntity> {
    val tail = compareByDescending<DocumentEntity> { it.lastOpenedAt }.thenBy { it.id }
    return when (sort) {
        LibrarySort.LAST_OPENED -> tail
        LibrarySort.NAME_ASC -> {
            val names = NaturalNameComparator(locale)
            Comparator<DocumentEntity> { a, b -> names.compare(a.title, b.title) }.then(tail)
        }
        LibrarySort.NAME_DESC -> {
            val names = NaturalNameComparator(locale)
            Comparator<DocumentEntity> { a, b -> names.compare(b.title, a.title) }.then(tail)
        }
        LibrarySort.DATE_ADDED -> compareByDescending<DocumentEntity> { it.createdAt }.then(tail)
        LibrarySort.SIZE -> compareByDescending<DocumentEntity> { it.sizeBytes }.then(tail)
    }
}

/**
 * File names the way a person sorts them: `file2` before `file10`, `a` beside `A`, and letters by the
 * locale's own alphabet (a Swedish reader finds Å after Z). Runs of ASCII digits compare as numbers of any
 * length; everything between them goes to a [Collator] at secondary strength, which ignores case but not
 * accents. Digits sort before letters. Two names that differ only in leading zeros (`file02`, `file2`)
 * compare equal here and are left to the caller's tie-break.
 */
internal class NaturalNameComparator(locale: Locale) : Comparator<String> {

    private val collator: Collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val digitsA = isDigit(a[i])
            val digitsB = isDigit(b[j])
            if (digitsA != digitsB) return if (digitsA) -1 else 1
            val endA = runEnd(a, i, digitsA)
            val endB = runEnd(b, j, digitsB)
            val order = if (digitsA) {
                compareNumbers(a, i, endA, b, j, endB)
            } else {
                collator.compare(a.substring(i, endA), b.substring(j, endB))
            }
            if (order != 0) return order
            i = endA
            j = endB
        }
        // One name is used up: the one with more left after the common part comes second.
        return (a.length - i).compareTo(b.length - j)
    }

    private fun isDigit(c: Char): Boolean = c in '0'..'9'

    private fun runEnd(s: String, from: Int, digits: Boolean): Int {
        var end = from
        while (end < s.length && isDigit(s[end]) == digits) end++
        return end
    }

    /** Digit runs as numbers without parsing them, so a 40-digit run cannot overflow anything. */
    private fun compareNumbers(a: String, fromA: Int, toA: Int, b: String, fromB: Int, toB: Int): Int {
        var startA = fromA
        while (startA < toA - 1 && a[startA] == '0') startA++
        var startB = fromB
        while (startB < toB - 1 && b[startB] == '0') startB++
        val lengthOrder = (toA - startA).compareTo(toB - startB)
        if (lengthOrder != 0) return lengthOrder
        for (k in 0 until toA - startA) {
            val order = a[startA + k].compareTo(b[startB + k])
            if (order != 0) return order
        }
        return 0
    }
}
