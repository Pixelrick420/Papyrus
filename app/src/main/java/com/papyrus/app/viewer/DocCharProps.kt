package com.papyrus.app.viewer

// Sprms apply in order onto what is already there, which a toggle needs: `0x81` means "the opposite of what the style gives".
internal object DocCharProps {
    const val CISTD = 0x4A30
    const val CPLAIN = 0x2A33

    private const val CFBOLD = 0x0835
    private const val CFITALIC = 0x0836
    private const val CFSTRIKE = 0x0837
    private const val CFDSTRIKE = 0x2A53
    private const val CKUL = 0x2A3E
    private const val CISS = 0x2A48
    private const val CHPSPOS = 0x4845
    private const val CICO = 0x2A42
    private const val CCV = 0x6870
    private const val CHIGHLIGHT = 0x2A0C
    private const val CSHD80 = 0x4866
    private const val CSHD = 0xCA71
    private const val CRGFTC0 = 0x4A4F
    private const val CRGFTC2 = 0x4A51

    private const val TOGGLE_SAME = 0x80
    private const val TOGGLE_OPPOSITE = 0x81
    private const val ISS_SUPER = 1
    private const val ISS_SUB = 2

    fun apply(props: RunProps, buf: ByteArray, from: Int, end: Int, fonts: DocFontTable) {
        // Colour, highlight and face each arrive as more than one sprm, the finer one preferred whatever
        // order they come in, so they are settled after the walk.
        var ico: Int? = null
        var colorRef: Int? = null
        var highlight: Int? = null
        var shading: Int? = null
        var asciiFont: Int? = null
        var otherFont: Int? = null
        val reader = DocSprmReader(buf, from, end)
        while (reader.next()) {
            when (reader.opcode) {
                CFBOLD -> props.bold = toggle(props.bold, reader.u8())
                CFITALIC -> props.italic = toggle(props.italic, reader.u8())
                CFSTRIKE, CFDSTRIKE -> props.strike = toggle(props.strike, reader.u8())
                CKUL -> props.underline = reader.u8() != 0
                CISS -> props.script = when (reader.u8()) {
                    ISS_SUPER -> ScriptShift.SUPER
                    ISS_SUB -> ScriptShift.SUB
                    else -> ScriptShift.NONE
                }
                CHPSPOS -> {
                    val shift = reader.i16()
                    props.script = when {
                        shift > 0 -> ScriptShift.SUPER
                        shift < 0 -> ScriptShift.SUB
                        else -> ScriptShift.NONE
                    }
                }
                CICO -> ico = DocColors.ico(reader.u8())
                CCV -> colorRef = DocColors.colorRef(buf, reader.operand)
                CHIGHLIGHT -> highlight = DocColors.ico(reader.u8())
                CSHD80 -> shading = DocColors.shd80(reader.u16())
                CSHD -> shading = DocColors.shd(buf, reader.operand + 1)
                CRGFTC0 -> asciiFont = reader.u16()
                CRGFTC2 -> otherFont = reader.u16()
            }
        }
        (colorRef ?: ico)?.let { props.color = it }
        (highlight ?: shading)?.let { props.background = it }
        (asciiFont ?: otherFont)?.let { props.mono = fonts.isMono(it) }
    }

    /** A toggle operand: 0 off, 1 on, 0x80 as the style has it, 0x81 the opposite of that. */
    private fun toggle(current: Boolean?, operand: Int): Boolean? = when (operand) {
        0 -> false
        1 -> true
        TOGGLE_SAME -> current
        TOGGLE_OPPOSITE -> current != true
        else -> current
    }

    class Scan(val styleIstd: Int, val plain: Boolean)

    fun scan(buf: ByteArray, from: Int, end: Int): Scan {
        var istd = 0
        var plain = false
        val reader = DocSprmReader(buf, from, end)
        while (reader.next()) {
            when (reader.opcode) {
                CISTD -> istd = reader.u16()
                CPLAIN -> plain = true
            }
        }
        return Scan(istd, plain)
    }
}
