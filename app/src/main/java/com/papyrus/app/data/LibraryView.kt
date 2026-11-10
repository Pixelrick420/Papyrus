package com.papyrus.app.data

import java.text.Collator
import java.util.Locale

enum class LibrarySort {
    LAST_OPENED,
    NAME_ASC,
    NAME_DESC,

    DATE_ADDED,

    SIZE,
}

// Word's two generations are one chip; a file with no viewer ([DocumentFormat.UNKNOWN]) belongs to no group.
enum class FormatGroup {
    PDF,
    WORD,
    ODT,
    EPUB,
    MARKDOWN,
    TEXT,
    CSV,

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

private fun extensionOf(title: String): String {
    val name = title.lowercase()
    return if (name.contains('.')) name.substringAfterLast('.') else ""
}

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

fun availableGroups(documents: List<DocumentEntity>): List<FormatGroup> {
    val present = documents.mapNotNullTo(HashSet()) { FormatGroup.of(it) }
    return FormatGroup.entries.filter { it in present }
}

// Filename matches come first, then content matches; each group follows [sort].
fun libraryView(
    documents: List<DocumentEntity>,
    query: String,
    filter: LibraryFilter,
    sort: LibrarySort,
    alsoMatching: Set<Long> = emptySet(),
    locale: Locale = Locale.getDefault(),
): List<DocumentEntity> {
    val needle = query.trim()
    val kept = documents.filter {
        filter.matches(it) &&
            (needle.isEmpty() || it.title.contains(needle, ignoreCase = true) || it.id in alsoMatching)
    }
    val comparator = comparatorFor(sort, locale)
    if (needle.isEmpty()) {
        return kept.sortedWith(comparator)
    }
    val (filenameMatches, contentMatches) = kept.partition { it.title.contains(needle, ignoreCase = true) }
    return filenameMatches.sortedWith(comparator) + contentMatches.sortedWith(comparator)
}

// Equal documents tie-break by most recently opened, then id, so the list is stable across emissions and animateItem moves only changed rows.
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

// `file2` before `file10`; letters by the locale's alphabet. Digit runs compare as numbers, the rest via a secondary-strength Collator.
// Names differing only in leading zeros compare equal and are left to the caller's tie-break.
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
