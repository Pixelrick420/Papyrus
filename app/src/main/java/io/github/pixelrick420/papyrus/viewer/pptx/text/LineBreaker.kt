package io.github.pixelrick420.papyrus.viewer.pptx.text

import java.util.Locale

/** The offsets a line may start at, from a locale's line-breaking rules. */
internal fun interface BreakProvider {

    fun boundaries(text: CharSequence, locale: Locale): List<Int>
}

/**
 * ICU's line iterator, which carries the dictionary-based Thai, Lao and Khmer breaking the platform
 * does not expose through `java.text` (plan §4.7 step 4).
 */
internal object IcuLineBreaks : BreakProvider {

    override fun boundaries(text: CharSequence, locale: Locale): List<Int> {
        if (text.length < 2) return emptyList()
        val iterator = android.icu.text.BreakIterator.getLineInstance(locale)
        iterator.setText(text.toString())
        val boundaries = ArrayList<Int>()
        var index = iterator.next()
        while (index != android.icu.text.BreakIterator.DONE && index < text.length) {
            if (index > 0) boundaries += index
            index = iterator.next()
        }
        return boundaries
    }
}

/**
 * ICU's break positions with PowerPoint's own rules applied on top: `latinLnBrk`, `eaLnBrk`,
 * `hangingPunct` and the East-Asian kinsoku sets (plan §4.7 step 4).
 */
internal class LineBreaker(private val provider: BreakProvider = IcuLineBreaks) {

    /** True at [offset] when a line may start there; the caller still ends a paragraph where it must. */
    fun breakBefore(
        text: CharSequence,
        properties: TextParagraphProperties,
        locale: Locale = Locale.ROOT,
    ): BooleanArray {
        val allowed = BooleanArray(text.length + 1)
        if (text.length < 2) return allowed
        for (offset in provider.boundaries(text, locale)) {
            if (offset in 1 until text.length) allowed[offset] = true
        }
        for (offset in 1 until text.length) {
            val left = Character.codePointBefore(text, offset)
            val right = Character.codePointAt(text, offset)
            allowed[offset] = if (closes(right) && properties.hangingPunctuation) {
                true
            } else {
                allowed[offset] && keepsTogether(left, right, properties)
            }
        }
        return allowed
    }

    private fun keepsTogether(
        left: Int,
        right: Int,
        properties: TextParagraphProperties,
    ): Boolean = when {
        left == NO_BREAK_SPACE || left == NARROW_NO_BREAK_SPACE -> false
        closes(right) || opens(left) -> false
        !properties.eastAsianLineBreak && isEastAsian(left) != isEastAsian(right) -> false
        !properties.latinLineBreak && isLatin(left) && isLatin(right) -> false
        else -> true
    }

    private fun isEastAsian(codePoint: Int): Boolean = FontSlots.slotOf(codePoint) == FontSlot.EAST_ASIAN

    private fun isLatin(codePoint: Int): Boolean =
        FontSlots.slotOf(codePoint) == FontSlot.LATIN && !Character.isWhitespace(codePoint)

    private fun closes(codePoint: Int): Boolean = codePoint in CLOSERS

    private fun opens(codePoint: Int): Boolean = codePoint in OPENERS

    private companion object {

        const val NO_BREAK_SPACE = 0x00A0

        const val NARROW_NO_BREAK_SPACE = 0x202F

        /** Closers and small kana a kinsoku line may not start with. */
        val CLOSERS: Set<Int> = "、。，．・：；？！ゝゞヽヾー々〆）〕］｝〉》」』】〙〗〟’”｠»".map { it.code }.toSet()

        /** Openers an East-Asian line may not end with. */
        val OPENERS: Set<Int> = "（〔［｛〈《「『【〘〖〝‘“｟«".map { it.code }.toSet()
    }
}
