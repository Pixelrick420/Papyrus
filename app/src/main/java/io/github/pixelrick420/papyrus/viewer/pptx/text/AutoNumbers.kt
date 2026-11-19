package io.github.pixelrick420.papyrus.viewer.pptx.text

/**
 * Builds the marker `buAutoNum` asks for, counting per level.
 *
 * Numbering is a sequence rather than a property of one paragraph: a shallower numbered paragraph
 * restarts every deeper counter, and an unnumbered paragraph restarts its own level and the ones
 * below it. [markers] is the whole-body pass that holds those counters. The text it returns is
 * drawn beside the paragraph and never merged into its runs, which is what keeps find offsets on
 * the document's own words.
 */
internal class AutoNumbers {

    fun markers(body: TextBody): List<String?> = markers(body.paragraphs)

    fun markers(paragraphs: List<TextParagraph>): List<String?> {
        val counts = IntArray(LEVELS)
        val started = BooleanArray(LEVELS)
        return paragraphs.map { paragraph ->
            val level = paragraph.level.coerceIn(0, LEVELS - 1)
            val bullet = paragraph.properties.bullet as? TextBullet.Numbering
            if (bullet != null && isNumbered(bullet.scheme)) {
                for (deeper in level + 1 until LEVELS) started[deeper] = false
                val value = if (started[level]) counts[level] + 1 else bullet.startAt ?: DEFAULT_START
                counts[level] = value
                started[level] = true
                format(bullet.scheme, value)
            } else {
                for (restart in level until LEVELS) started[restart] = false
                null
            }
        }
    }

    /** Null when [scheme] draws nothing, which `buNone` and `type="none"` both mean. */
    fun format(scheme: String, value: Int): String? {
        if (!isNumbered(scheme)) return null
        return decorate(scheme, body(scheme, value))
    }

    private fun body(scheme: String, value: Int): String = when {
        scheme.startsWith("alphaLc") -> letters(value, upper = false)
        scheme.startsWith("alphaUc") -> letters(value, upper = true)
        scheme.startsWith("romanLc") -> roman(value).lowercase()
        scheme.startsWith("romanUc") -> roman(value)
        scheme.startsWith("circleNum") -> circled(scheme, value)
        scheme.startsWith("decimalEnclosed") -> enclosed(scheme, value)
        scheme == "decimalZero" -> value.coerceAtLeast(0).toString().padStart(2, '0')
        scheme in FULL_WIDTH_DECIMALS -> fullWidth(value)
        scheme.startsWith("arabic1") -> digitsOf(value, ARABIC_INDIC_ZERO)
        scheme.startsWith("arabic2") -> digitsOf(value, EXTENDED_ARABIC_INDIC_ZERO)
        scheme.startsWith("hindiNum") -> digitsOf(value, DEVANAGARI_ZERO)
        scheme.startsWith("hindiAlpha") -> lettersOf(HINDI_LETTERS, value)
        scheme.startsWith("thaiNum") -> digitsOf(value, THAI_ZERO)
        scheme.startsWith("thaiAlpha") -> lettersOf(THAI_LETTERS, value)
        scheme == "hebrew2Minus" -> hebrew(value)
        scheme.startsWith("ea1Chs") || scheme.startsWith("ea1Cht") || scheme == "ea2CombinedPeriod" ->
            ideographic(value, CHINESE_DIGITS, CHINESE_UNITS)
        scheme.startsWith("ea1JpnKor") -> lettersOf(HANGUL_LETTERS, value)
        scheme in IDEOGRAPHIC_COUNTING -> ideographic(value, CHINESE_DIGITS, CHINESE_UNITS)
        scheme in IDEOGRAPHIC_LEGAL -> ideographic(value, CHINESE_LEGAL_DIGITS, CHINESE_LEGAL_UNITS)
        scheme in IDEOGRAPHIC_DIGITAL -> ideographicDigits(value, CHINESE_DIGITS)
        scheme == "chineseCountingThousand" || scheme == "taiwaneseCountingThousand" ->
            ideographic(value * 1000, CHINESE_DIGITS, CHINESE_UNITS)
        scheme == "japaneseLegal" -> ideographic(value, JAPANESE_LEGAL_DIGITS, CHINESE_LEGAL_UNITS)
        scheme == "koreanCounting" -> ideographic(value, HANGUL_DIGITS, HANGUL_UNITS)
        scheme == "koreanHanjaCounting" -> ideographic(value, CHINESE_DIGITS, CHINESE_UNITS)
        scheme == "koreanDigital" -> ideographicDigits(value, HANGUL_DIGITS, HANGUL_ZERO)
        scheme == "koreanDigital2" -> nativeKorean(value)
        scheme == "ideographTraditional" -> cycle(value, TRADITIONAL_CYCLE)
        scheme == "ideographZodiac" -> cycle(value, ZODIAC_BRANCHES)
        scheme == "ideographZodiacTraditional" -> cycle(value, ZODIAC_ANIMALS)
        else -> value.toString()
    }

    private fun decorate(scheme: String, text: String): String = when {
        scheme.endsWith("ParenBoth") -> "($text)"
        scheme.endsWith("ParenR") -> "$text)"
        scheme.endsWith("Period") -> "$text."
        scheme.endsWith("Minus") -> "$text-"
        else -> text
    }

    private fun letters(value: Int, upper: Boolean): String {
        if (value <= 0) return value.toString()
        val letter = ('a'.code + (value - 1) % 26).toChar()
        return letter.toString().repeat((value - 1) / 26 + 1).let { if (upper) it.uppercase() else it }
    }

    private fun lettersOf(alphabet: String, value: Int): String =
        if (value in 1..alphabet.length) alphabet[value - 1].toString() else value.toString()

    private fun roman(value: Int): String {
        if (value <= 0 || value >= 4000) return value.toString()
        val builder = StringBuilder()
        var remaining = value
        for ((amount, numeral) in ROMAN_NUMERALS) {
            while (remaining >= amount) {
                builder.append(numeral)
                remaining -= amount
            }
        }
        return builder.toString()
    }

    private fun circled(scheme: String, value: Int): String {
        val (base, extent) = when {
            scheme.startsWith("circleNumWdBlackPlain") -> 0x278A to 10
            scheme.startsWith("circleNumWdWhitePlain") -> 0x2780 to 10
            else -> 0x2460 to 20
        }
        return if (value in 1..extent) String(Character.toChars(base + value - 1)) else value.toString()
    }

    private fun enclosed(scheme: String, value: Int): String {
        val (base, extent) = when {
            scheme == "decimalEnclosedFullstop" -> 0x2488 to 20
            scheme == "decimalEnclosedParen" -> 0x2474 to 20
            else -> 0x2460 to 20
        }
        if (value in 1..extent) return String(Character.toChars(base + value - 1))
        return when {
            scheme == "decimalEnclosedFullstop" -> "$value."
            scheme == "decimalEnclosedParen" -> "($value)"
            else -> value.toString()
        }
    }

    private fun fullWidth(value: Int): String = digitsOf(value, FULL_WIDTH_ZERO)

    private fun digitsOf(value: Int, zero: Int): String {
        if (value < 0) return value.toString()
        return value.toString().map { (zero + (it - '0')).toChar() }.joinToString("")
    }

    private fun ideographic(value: Int, digits: String, units: String): String {
        if (value <= 0) return value.toString()
        val groups = ArrayList<Int>()
        var remaining = value
        while (remaining > 0) {
            groups += remaining % 10_000
            remaining /= 10_000
        }
        val builder = StringBuilder()
        for (index in groups.indices.reversed()) {
            val group = groups[index]
            if (group == 0) {
                if (builder.isNotEmpty() && builder.last() != ZERO) builder.append(ZERO)
                continue
            }
            if (builder.isNotEmpty() && group < 1000 && builder.last() != ZERO) builder.append(ZERO)
            builder.append(groupText(group, digits, units))
            builder.append(GROUP_UNITS.getOrElse(index) { "" })
        }
        if (value in 10..19 && builder.startsWith(digits[0])) builder.deleteCharAt(0)
        return builder.toString()
    }

    private fun groupText(group: Int, digits: String, units: String): String {
        val builder = StringBuilder()
        var zero = false
        for (position in POSITIONS.indices.reversed()) {
            val digit = group / POSITIONS[position] % 10
            if (digit == 0) {
                if (builder.isNotEmpty()) zero = true
                continue
            }
            if (zero) {
                builder.append(ZERO)
                zero = false
            }
            builder.append(digits[digit - 1])
            if (position > 0) builder.append(units[position - 1])
        }
        return builder.toString()
    }

    private fun ideographicDigits(value: Int, digits: String, zero: String = ZERO.toString()): String {
        if (value < 0) return value.toString()
        return value.toString().map { if (it == '0') zero else digits[it - '1'] }.joinToString("")
    }

    private fun nativeKorean(value: Int): String = when (value) {
        in 1..10 -> NATIVE_KOREAN[value - 1]
        in 11..19 -> "열" + NATIVE_KOREAN[value - 11]
        else -> ideographicDigits(value, HANGUL_DIGITS, HANGUL_ZERO)
    }

    private fun cycle(value: Int, cycle: String): String =
        if (value <= 0) value.toString() else cycle[(value - 1) % cycle.length].toString()

    private fun hebrew(value: Int): String {
        if (value !in 1..999) return value.toString()
        val builder = StringBuilder()
        var remaining = value / 100
        while (remaining > 0) {
            builder.append('ת')
            remaining--
        }
        val rest = value % 100
        when {
            rest == 15 -> builder.append("טו")
            rest == 16 -> builder.append("טז")
            else -> {
                HEBREW_TENS[rest / 10]?.let(builder::append)
                HEBREW_UNITS[rest % 10]?.let(builder::append)
            }
        }
        return builder.toString()
    }

    private fun isNumbered(scheme: String): Boolean = scheme.isNotEmpty() && scheme != "none"

    private companion object {
        const val LEVELS = 9

        const val DEFAULT_START = 1

        const val ZERO = '零'

        val POSITIONS = intArrayOf(1, 10, 100, 1000)

        const val CHINESE_DIGITS = "一二三四五六七八九"

        const val CHINESE_UNITS = "十百千"

        const val CHINESE_LEGAL_DIGITS = "壹贰叁肆伍陆柒捌玖"

        const val CHINESE_LEGAL_UNITS = "拾佰仟"

        const val JAPANESE_LEGAL_DIGITS = "壱弐参肆伍陸柒捌玖"

        const val HANGUL_DIGITS = "일이삼사오육칠팔구"

        const val HANGUL_UNITS = "십백천"

        const val HANGUL_ZERO = "영"

        val GROUP_UNITS = arrayOf("", "万", "亿")

        const val HINDI_LETTERS = "कखगघङचछजझञटठडढणतथदधनपफबभमयरलवशषसह"

        const val THAI_LETTERS = "กขฃคฅฆงจฉชซฌญฎฏฐฑฒณดตถทธนบปผฝพฟภมยรลวศษสหฬอฮ"

        const val HANGUL_LETTERS = "가나다라마바사아자차카타파하"

        val NATIVE_KOREAN = listOf("하나", "둘", "셋", "넷", "다섯", "여섯", "일곱", "여덟", "아홉", "열")

        const val TRADITIONAL_CYCLE = "甲乙丙丁戊己庚辛壬癸"

        const val ZODIAC_BRANCHES = "子丑寅卯辰巳午未申酉戌亥"

        const val ZODIAC_ANIMALS = "鼠牛虎兔龍蛇馬羊猴雞狗豬"

        val HEBREW_UNITS = arrayOf("", "א", "ב", "ג", "ד", "ה", "ו", "ז", "ח", "ט")

        val HEBREW_TENS = arrayOf("", "י", "כ", "ל", "מ", "נ", "ס", "ע", "פ", "צ")

        val ROMAN_NUMERALS = listOf(
            1000 to "M",
            900 to "CM",
            500 to "D",
            400 to "CD",
            100 to "C",
            90 to "XC",
            50 to "L",
            40 to "XL",
            10 to "X",
            9 to "IX",
            5 to "V",
            4 to "IV",
            1 to "I",
        )

        const val ARABIC_INDIC_ZERO = 0x0660

        const val EXTENDED_ARABIC_INDIC_ZERO = 0x06F0

        const val DEVANAGARI_ZERO = 0x0966

        const val THAI_ZERO = 0x0E50

        const val FULL_WIDTH_ZERO = 0xFF10

        val FULL_WIDTH_DECIMALS = setOf("decimalFullWidth", "decimalFullWidth2", "arabicDbPlain", "arabicDbPeriod")

        val IDEOGRAPHIC_COUNTING = setOf(
            "chineseCounting",
            "taiwaneseCounting",
            "japaneseCounting",
            "japaneseDigitalTenThousand",
        )

        val IDEOGRAPHIC_LEGAL = setOf("chineseLegalSimplified", "ideographLegal", "koreanLegal")

        val IDEOGRAPHIC_DIGITAL = setOf("ideographDigital", "taiwaneseDigital")
    }
}
