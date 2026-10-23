package com.papyrus.app.viewer

/**
 * Colours as Word's binary format writes them: an `ico` palette index, a COLORREF, or a SHD that
 * pairs a foreground with a background under a pattern. All results are `0xRRGGBB`, [COLOR_AUTO] for
 * "the theme's own", or null where the file says nothing.
 */
internal object DocColors {

    /** `ico` 1 to 16 of Word's 16-colour palette; 0 is automatic. Null for an index past it. */
    fun ico(index: Int): Int? = when (index) {
        0 -> COLOR_AUTO
        in 1..ICO.size -> ICO[index - 1]
        else -> null
    }

    /** A COLORREF at [at]: red, green, blue, then 0xFF if the colour is automatic. */
    fun colorRef(buf: ByteArray, at: Int): Int {
        if (at < 0 || at + 4 > buf.size) return COLOR_AUTO
        if (buf[at + 3].toInt() and 0xFF == AUTO_FLAG) return COLOR_AUTO
        return ((buf[at].toInt() and 0xFF) shl 16) or ((buf[at + 1].toInt() and 0xFF) shl 8) or (buf[at + 2].toInt() and 0xFF)
    }

    /** A 10-byte SHD at [at]: foreground and background COLORREFs, then the pattern. */
    fun shd(buf: ByteArray, at: Int): Int? {
        if (at < 0 || at + SHD_BYTES > buf.size) return null
        return fill(colorRef(buf, at), colorRef(buf, at + 4), u16(buf, at + 8))
    }

    /** A 2-byte SHD80: 5-bit foreground and background `ico`s, then a 6-bit pattern. */
    fun shd80(value: Int): Int? {
        val fore = ico(value and ICO_MASK) ?: COLOR_AUTO
        val back = ico((value ushr BACK_SHIFT) and ICO_MASK) ?: COLOR_AUTO
        return fill(fore, back, value ushr PATTERN_SHIFT)
    }

    /**
     * The colour a shading paints, or null if it paints none. Clear shows the background and solid the
     * foreground; a percentage pattern is the two blended by its coverage, which is how it reads at
     * text size. Line patterns (stripes, crosshatch) have no single colour and count as clear.
     * Automatic is black for a foreground and white for a background.
     */
    private fun fill(fore: Int, back: Int, pattern: Int): Int? {
        val foreRgb = if (fore == COLOR_AUTO) BLACK else fore
        val backRgb = if (back == COLOR_AUTO) WHITE else back
        return when (pattern) {
            PATTERN_SOLID -> foreRgb
            in PATTERN_PERCENT_FIRST..PATTERN_PERCENT_LAST -> blend(backRgb, foreRgb, PERCENT[pattern - PATTERN_PERCENT_FIRST])
            else -> back.takeIf { it != COLOR_AUTO }
        }
    }

    private fun blend(back: Int, fore: Int, percent: Int): Int {
        var out = 0
        for (shift in intArrayOf(RED_SHIFT, GREEN_SHIFT, 0)) {
            val b = (back ushr shift) and 0xFF
            val f = (fore ushr shift) and 0xFF
            out = out or (((b * (100 - percent) + f * percent) / 100) shl shift)
        }
        return out
    }

    private val ICO = intArrayOf(
        0x000000, 0x0000FF, 0x00FFFF, 0x00FF00, 0xFF00FF, 0xFF0000, 0xFFFF00, 0xFFFFFF,
        0x000080, 0x008080, 0x008000, 0x800080, 0x800000, 0x808000, 0x808080, 0xC0C0C0,
    )

    /** Coverage of patterns 2 to 13: 5, 10, 20, 25, 30, 40, 50, 60, 70, 75, 80 and 90 percent. */
    private val PERCENT = intArrayOf(5, 10, 20, 25, 30, 40, 50, 60, 70, 75, 80, 90)

    private const val AUTO_FLAG = 0xFF
    private const val SHD_BYTES = 10
    private const val ICO_MASK = 0x1F
    private const val BACK_SHIFT = 5
    private const val PATTERN_SHIFT = 10
    private const val PATTERN_SOLID = 1
    private const val PATTERN_PERCENT_FIRST = 2
    private const val PATTERN_PERCENT_LAST = 13
    private const val BLACK = 0x000000
    private const val WHITE = 0xFFFFFF
    private const val RED_SHIFT = 16
    private const val GREEN_SHIFT = 8
}
