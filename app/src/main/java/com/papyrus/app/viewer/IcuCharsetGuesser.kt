package com.papyrus.app.viewer

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Statistical charset detector on the platform's own decoders: Android ships no `CharsetDetector`, and
 * ICU4J for one guess is not worth it. Scores each reading by prose-likeness, favouring lowercase.
 */
internal object StatisticalCharsetGuesser : CharsetGuesser {

    /** Decode at most this many bytes: enough to settle a guess, small enough to stay cheap. */
    private const val SAMPLE_LIMIT = 16 * 1024

    /** Below this share of bytes >= 0x80 the sample is mostly ASCII, and Windows-1252 is the better bet. */
    private const val MIN_HIGH_BYTE_SHARE = 0.25

    /** A non-Latin reading must beat the best Latin one by this or the Windows-1252 default stands. */
    private const val MIN_ADVANTAGE = 0.15

    /**
     * Text is mostly lowercase, so an uppercase letter is weaker evidence of a good reading than a
     * lowercase one.
     */
    private const val UPPERCASE_WEIGHT = 0.5

    /**
     * Latin-1 supplement and extended letters are common in real text, but also where a mis-read of a
     * non-Latin byte lands.
     */
    private const val LATIN_EXTENDED_WEIGHT = 0.6

    /**
     * Latin charsets the Windows-1252 fallback already covers; a reading under one of these never
     * overrides it.
     */
    private val LATIN_CHARSETS = setOf(
        "windows-1252", "ISO-8859-1", "ISO-8859-15", "ISO-8859-2", "windows-1250", "windows-1254",
    )

    override fun guess(sample: ByteArray, length: Int): String? {
        val size = minOf(length, SAMPLE_LIMIT)
        if (size == 0 || highByteShare(sample, size) < MIN_HIGH_BYTE_SHARE) return null

        var bestLatin = Double.NEGATIVE_INFINITY
        var bestOther = Double.NEGATIVE_INFINITY
        var bestOtherName: String? = null

        for (choice in TextEncodings.choices) {
            val name = choice.charsetName
            if (name.startsWith("UTF-", ignoreCase = true)) continue
            val charset = TextEncodings.charsetOrNull(name) ?: continue
            val score = score(sample, size, charset) ?: continue
            if (name in LATIN_CHARSETS) {
                if (score > bestLatin) bestLatin = score
            } else if (score > bestOther) {
                bestOther = score
                bestOtherName = name
            }
        }

        if (bestOtherName == null || bestOther - bestLatin < MIN_ADVANTAGE) return null
        return bestOtherName
    }

    /** The mean weight per decoded character, or null when [charset] cannot decode the sample cleanly. */
    private fun score(sample: ByteArray, length: Int, charset: Charset): Double? {
        val text = decode(sample, length, charset) ?: return null
        if (text.isEmpty()) return null
        var total = 0.0
        for (c in text) total += weight(c)
        return total / text.length
    }

    /** Strict: a malformed or unmappable byte means this is not the charset, not a character to replace. */
    private fun decode(sample: ByteArray, length: Int, charset: Charset): String? {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val chars = CharBuffer.allocate(length)
        val result = decoder.decode(ByteBuffer.wrap(sample, 0, length), chars, true)
        if (result.isError) return null
        decoder.flush(chars)
        chars.flip()
        return chars.toString()
    }

    private fun weight(c: Char): Double = when {
        c == '\uFFFD' -> -3.0
        isControl(c) -> -4.0
        Character.isLetter(c) || isCjkPunctuation(c) ->
            letterWeight(c) * if (Character.isUpperCase(c)) UPPERCASE_WEIGHT else 1.0
        Character.isDigit(c) || Character.isWhitespace(c) -> 0.5
        else -> 0.3
    }

    private fun letterWeight(c: Char): Double = if (isLatinExtended(c)) LATIN_EXTENDED_WEIGHT else 1.0

    /**
     * CJK punctuation is natural text as much as a letter: scoring it as a stray symbol would wrongly
     * prefer a reading that turns those bytes into an ideograph.
     */
    private fun isCjkPunctuation(c: Char): Boolean =
        c.code in 0x3000..0x303F || c.code in 0xFE30..0xFE4F || c.code in 0xFF00..0xFFEF

    /** C0 and C1 controls other than a tab or a line break: a charset that decodes to these is a misfire. */
    private fun isControl(c: Char): Boolean =
        (c.code < 0x20 && c != '\t' && c != '\n' && c != '\r') || c.code in 0x7F..0x9F

    /** Latin-1 supplement and the Latin extended blocks, where a mis-decoded high byte usually surfaces. */
    private fun isLatinExtended(c: Char): Boolean =
        c.code in 0x00C0..0x024F || c.code in 0x1E00..0x1EFF

    private fun highByteShare(sample: ByteArray, length: Int): Double {
        var high = 0
        for (i in 0 until length) if (sample[i].toInt() < 0) high++
        return high.toDouble() / length
    }
}
