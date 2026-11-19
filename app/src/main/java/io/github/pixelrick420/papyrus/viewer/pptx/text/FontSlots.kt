package io.github.pixelrick420.papyrus.viewer.pptx.text

/** The four typeface slots a character can take, in the order `a:rPr` declares them. */
internal enum class FontSlot { LATIN, EAST_ASIAN, COMPLEX_SCRIPT, SYMBOL }

/**
 * Picks the `latin`/`ea`/`cs`/`sym` slot by Unicode range and maps it through [FontSubstitution]
 * (plan §4.7 step 2). Instances are memoised so a repeat character resolves to the same [ResolvedFont].
 */
internal class FontSlots(private val substitution: FontSubstitution = FontSubstitution()) {

    private val resolved = HashMap<Key, ResolvedFont>()

    fun resolve(properties: TextRunProperties, codePoint: Int): ResolvedFont =
        resolve(properties, slotOf(codePoint))

    fun resolve(properties: TextRunProperties, slot: FontSlot): ResolvedFont {
        val (requested, hint) = faceOf(properties, slot)
        return resolved.getOrPut(Key(slot, requested, hint, properties.bold, properties.italic)) {
            ResolvedFont(
                substitution.substitute(requested, hint?.panose, hint?.pitchFamily),
                properties.bold,
                properties.italic,
            )
        }
    }

    private fun faceOf(properties: TextRunProperties, slot: FontSlot): Pair<String?, FontHint?> = when (slot) {
        FontSlot.LATIN -> properties.latinTypeface to properties.latinHint
        FontSlot.EAST_ASIAN -> properties.eastAsianTypeface?.let { it to properties.eastAsianHint }
            ?: (properties.latinTypeface to properties.latinHint)
        FontSlot.COMPLEX_SCRIPT -> properties.complexScriptTypeface?.let { it to properties.complexScriptHint }
            ?: (properties.latinTypeface to properties.latinHint)
        FontSlot.SYMBOL -> properties.symbolTypeface?.let { it to properties.symbolHint }
            ?: (properties.latinTypeface to properties.latinHint)
    }

    private data class Key(
        val slot: FontSlot,
        val requested: String?,
        val hint: FontHint?,
        val bold: Boolean,
        val italic: Boolean,
    )

    companion object {

        fun slotOf(codePoint: Int): FontSlot = when {
            inAny(codePoint, SYMBOLS) -> FontSlot.SYMBOL
            inAny(codePoint, EAST_ASIAN) -> FontSlot.EAST_ASIAN
            inAny(codePoint, COMPLEX_SCRIPT) -> FontSlot.COMPLEX_SCRIPT
            else -> FontSlot.LATIN
        }

        private fun inAny(codePoint: Int, ranges: List<IntRange>): Boolean =
            ranges.any { codePoint in it }

        private val SYMBOLS = listOf(
            0x2190..0x21FF,
            0x2300..0x23FF,
            0x25A0..0x25FF,
            0x2600..0x27BF,
            0x2B00..0x2BFF,
            0xE000..0xF8FF,
            0xF0000..0xFFFFD,
        )

        private val EAST_ASIAN = listOf(
            0x1100..0x11FF,
            0x2E80..0x2FFF,
            0x3000..0x30FF,
            0x3130..0x318F,
            0x31C0..0x31EF,
            0x3200..0x4DBF,
            0x4E00..0x9FFF,
            0xA960..0xA97F,
            0xAC00..0xD7AF,
            0xF900..0xFAFF,
            0xFE30..0xFE4F,
            0xFF01..0xFF60,
            0xFFE0..0xFFE6,
            0x1F200..0x1F2FF,
            0x20000..0x2FA1F,
        )

        private val COMPLEX_SCRIPT = listOf(
            0x0590..0x05FF,
            0x0600..0x07BF,
            0x0900..0x0DFF,
            0x0E00..0x0E7F,
            0x0E80..0x0EFF,
            0x0F00..0x0FFF,
            0x1000..0x109F,
            0x10A0..0x10FF,
            0x1200..0x137F,
            0x13A0..0x13FF,
            0x1400..0x167F,
            0x1680..0x169F,
            0x16A0..0x16FF,
            0x1700..0x17FF,
            0x1800..0x18AF,
            0xFB1D..0xFDFF,
            0xFE70..0xFEFF,
        )
    }
}
