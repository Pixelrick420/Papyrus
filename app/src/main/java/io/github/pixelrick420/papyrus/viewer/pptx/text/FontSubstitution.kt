package io.github.pixelrick420.papyrus.viewer.pptx.text

/**
 * What a requested typeface maps to, and whether line breaks can be trusted to match the deck.
 *
 * [compatible] is false when the advances are not designed to match, so the File-info sheet lists the
 * substitution and the user knows why wrapping may differ.
 */
internal sealed interface FontChoice {

    val requested: String?

    val compatible: Boolean

    /** True when the drawn family is not the one the deck asked for. */
    val substituted: Boolean

    class Bundled(
        override val requested: String?,
        val font: BundledFont,
        override val compatible: Boolean,
    ) : FontChoice {
        override val substituted: Boolean
            get() = requested != null && !requested.equals(font.family, ignoreCase = true)
    }

    class System(
        override val requested: String?,
        val family: String,
        override val compatible: Boolean,
    ) : FontChoice {
        override val substituted: Boolean
            get() = requested != null && !requested.equals(family, ignoreCase = true)
    }

    /** Android's own fallback chain: CJK, Arabic, Indic, emoji and symbol faces. */
    class Platform(override val requested: String?) : FontChoice {
        override val compatible: Boolean get() = false

        override val substituted: Boolean get() = requested != null
    }
}

/**
 * Maps a requested typeface onto one the app can draw: a bundled metric-compatible family by name, an
 * installed family of exactly that name, a `panose`/`pitchFamily` class fallback, then Android's chain.
 *
 * Spike S3: Calibri Light has no metric-compatible open font and Carlito has no Light weight, and neither
 * Aptos nor Segoe UI has one either. The C-FONT control deck that measures the drift is not in the repo, so
 * the decision is to draw Calibri Light in Carlito Regular, flag it in File info and prefer an embedded font
 * in M6; Aptos and Segoe UI take Roboto and are flagged the same way. `FontSubstitutionSpikeTest` records the
 * substitute's width against the system sans face until C-FONT supplies the requested widths.
 */
internal class FontSubstitution(
    private val installed: (String) -> Boolean = { false },
) {

    fun substitute(family: String?, panose: String? = null, pitchFamily: String? = null): FontChoice {
        val requested = family?.trim()?.takeIf { it.isNotEmpty() }
            ?: return FontChoice.System(null, SYSTEM_SANS, compatible = true)
        table[normalize(requested)]?.let { return it.choice(requested) }
        if (installed(requested)) return FontChoice.System(requested, requested, compatible = true)
        return when (hintOf(panose, pitchFamily)) {
            Hint.SERIF -> FontChoice.System(requested, SYSTEM_SERIF, compatible = false)
            Hint.FIXED -> FontChoice.Bundled(requested, BundledFont.COUSINE, compatible = false)
            Hint.SYMBOL -> FontChoice.Platform(requested)
            Hint.SANS -> FontChoice.System(requested, SYSTEM_SANS, compatible = false)
        }
    }

    companion object {

        const val SYSTEM_SANS = "sans-serif"

        const val SYSTEM_SERIF = "serif"

        private const val PANOSE_LATIN_TEXT = 2

        private const val PANOSE_LATIN_SYMBOL = 5

        private const val PANOSE_SERIF_STYLE_MIN = 2

        private const val PANOSE_SERIF_STYLE_MAX = 10

        private const val PANOSE_SANS_STYLE_MIN = 11

        private const val PANOSE_SANS_STYLE_MAX = 13

        private const val PANOSE_SERIF_STYLE_OFFSET = 2

        private const val PANOSE_SERIF_STYLE_LENGTH = 2

        private const val PITCH_FAMILY_MASK = 0xF0

        private const val PITCH_FAMILY_ROMAN = 0x10

        private const val PITCH_FAMILY_SWISS = 0x20

        private const val PITCH_FAMILY_MODERN = 0x30

        private val WHITESPACE = Regex("\\s+")

        private val table: Map<String, Target> = buildMap {
            fun add(names: String, target: Target) {
                names.split(',').forEach { put(normalize(it), target) }
            }
            add("Calibri", Target.Bundled(BundledFont.CARLITO, compatible = true))
            add("Calibri Light", Target.Bundled(BundledFont.CARLITO, compatible = false))
            add("Cambria", Target.Bundled(BundledFont.CALADEA, compatible = true))
            add("Arial", Target.Bundled(BundledFont.ARIMO, compatible = true))
            add("Times New Roman", Target.Bundled(BundledFont.TINOS, compatible = true))
            add("Courier New", Target.Bundled(BundledFont.COUSINE, compatible = true))
            add("Helvetica, Helvetica Neue", Target.Bundled(BundledFont.ARIMO, compatible = false))
            add("Arial Narrow, Arial Black, Impact", Target.Bundled(BundledFont.ARIMO, compatible = false))
            add("Consolas, Lucida Console, Menlo", Target.Bundled(BundledFont.COUSINE, compatible = false))
            add("Segoe UI, Aptos, Aptos Display, Aptos Narrow", Target.System(SYSTEM_SANS, compatible = false))
            add("Verdana, Tahoma, Trebuchet MS", Target.System(SYSTEM_SANS, compatible = false))
            add("Georgia, Garamond, Palatino, Book Antiqua", Target.System(SYSTEM_SERIF, compatible = false))
            add("Symbol, Wingdings, Wingdings 2, Wingdings 3, Webdings", Target.Platform)
            add(
                "MS Gothic, Yu Gothic, Meiryo, Malgun Gothic, SimSun, Microsoft YaHei",
                Target.Platform,
            )
        }

        private fun normalize(name: String): String = name.trim().lowercase().replace(WHITESPACE, " ")

        private fun hintOf(panose: String?, pitchFamily: String?): Hint =
            panoseHint(panose) ?: pitchHint(pitchFamily) ?: Hint.SANS

        private fun panoseHint(panose: String?): Hint? {
            val hex = panose?.trim()?.replace(" ", "")?.takeIf { it.length >= 4 } ?: return null
            val familyKind = hex.substring(0, 2).toIntOrNull(16) ?: return null
            if (familyKind == PANOSE_LATIN_SYMBOL) return Hint.SYMBOL
            if (familyKind != PANOSE_LATIN_TEXT) return null
            val serifStyle = hex
                .substring(PANOSE_SERIF_STYLE_OFFSET, PANOSE_SERIF_STYLE_OFFSET + PANOSE_SERIF_STYLE_LENGTH)
                .toIntOrNull(16) ?: return null
            return when (serifStyle) {
                in PANOSE_SERIF_STYLE_MIN..PANOSE_SERIF_STYLE_MAX -> Hint.SERIF
                in PANOSE_SANS_STYLE_MIN..PANOSE_SANS_STYLE_MAX -> Hint.SANS
                else -> null
            }
        }

        private fun pitchHint(pitchFamily: String?): Hint? {
            val value = pitchFamily?.trim()?.toIntOrNull(16) ?: return null
            return when (value and PITCH_FAMILY_MASK) {
                PITCH_FAMILY_MODERN -> Hint.FIXED
                PITCH_FAMILY_ROMAN -> Hint.SERIF
                PITCH_FAMILY_SWISS -> Hint.SANS
                else -> null
            }
        }
    }
}

private enum class Hint { SERIF, SANS, FIXED, SYMBOL }

private sealed interface Target {

    fun choice(requested: String): FontChoice

    class Bundled(private val font: BundledFont, private val compatible: Boolean) : Target {

        override fun choice(requested: String): FontChoice = FontChoice.Bundled(requested, font, compatible)
    }

    class System(private val family: String, private val compatible: Boolean) : Target {

        override fun choice(requested: String): FontChoice = FontChoice.System(requested, family, compatible)
    }

    data object Platform : Target {

        override fun choice(requested: String): FontChoice = FontChoice.Platform(requested)
    }
}
