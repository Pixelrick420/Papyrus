package io.github.pixelrick420.papyrus.viewer.pptx

import io.github.pixelrick420.papyrus.viewer.OutlineEntry
import io.github.pixelrick420.papyrus.viewer.cleanOutlineTitle

/** Section names become parent entries over the slides they cover; every other slide is its own entry. */
internal fun slideOutline(
    info: PresentationInfo,
    titles: List<String>,
    fallbackTitle: (Int) -> String = { index -> "Slide ${index + 1}" },
): List<OutlineEntry> {
    val indexOfId = info.slides.mapIndexed { index, ref -> ref.id to index }.toMap()
    val groups = info.sections.mapNotNull { section ->
        val indexes = section.slideIds.mapNotNull { indexOfId[it] }.distinct().sorted()
        val name = section.name?.let(::cleanOutlineTitle).orEmpty()
        if (indexes.isEmpty() || name.isEmpty()) null else Group(name, indexes)
    }
    val groupsByFirst = groups.associateBy { it.indexes.first() }
    val grouped = groups.flatMapTo(HashSet()) { it.indexes }

    fun titleOf(index: Int): String =
        titles.getOrNull(index)?.let(::cleanOutlineTitle)?.takeIf { it.isNotEmpty() } ?: fallbackTitle(index)

    val entries = ArrayList<OutlineEntry>(info.slides.size + groups.size)
    for (index in info.slides.indices) {
        val group = groupsByFirst[index]
        when {
            group != null -> {
                entries += OutlineEntry(index, 0, group.name)
                for (slide in group.indexes) {
                    if (slide != index) entries += OutlineEntry(slide, 1, titleOf(slide))
                }
            }
            index in grouped -> Unit
            else -> entries += OutlineEntry(index, 0, titleOf(index))
        }
    }
    return entries
}

private class Group(val name: String, val indexes: List<Int>)
