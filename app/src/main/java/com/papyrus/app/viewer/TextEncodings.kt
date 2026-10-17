package com.papyrus.app.viewer

import java.nio.charset.Charset

/** How a [TextEncoding] was reached; the picker shows it and the tests pin it. */
enum class EncodingSource { BOM, ASCII, UTF8, UTF16_PATTERN, GUESSED, FALLBACK, MANUAL }

/** [bomLength] leading bytes are a byte-order mark, not text, and are skipped before decoding. */
data class TextEncoding(
    val charset: Charset,
    val bomLength: Int = 0,
    val source: EncodingSource = EncodingSource.FALLBACK,
) {
    val name: String get() = charset.name()
}

/** A statistical opinion on bytes that are not valid UTF-8: a charset name, or null for none. */
fun interface CharsetGuesser {
    fun guess(sample: ByteArray, length: Int): String?
}

data class EncodingChoice(val charsetName: String, val label: String)

/**
 * Picks a charset for a file's bytes: BOM, UTF-16 without one, strict UTF-8, the platform detector,
 * then Windows-1252, which decodes every byte and is what a "Latin-1" file nearly always is.
 */
object TextEncodings {

    /** Enough for a statistical guess to settle, small enough to read before anything is shown. */
    const val SAMPLE_BYTES = 64 * 1024

    /** A zero byte beside a share of the sample's byte pairs this large marks UTF-16 without a BOM. */
    private const val UTF16_ZERO_SHARE_PERCENT = 10

    /**
     * The other half of each pair is at most this fraction of the first: real UTF-16 is lopsided, binary is not.
     */
    private const val UTF16_LOPSIDED_FACTOR = 10

    val WINDOWS_1252: Charset = Charset.forName("windows-1252")
    private val UTF_32LE: Charset = Charset.forName("UTF-32LE")
    private val UTF_32BE: Charset = Charset.forName("UTF-32BE")

    /** Charsets the picker offers, most common first; unsupported ones on this device are dropped. */
    val choices: List<EncodingChoice> = listOf(
        EncodingChoice("UTF-8", "UTF-8"),
        EncodingChoice("UTF-16LE", "UTF-16 LE"),
        EncodingChoice("UTF-16BE", "UTF-16 BE"),
        EncodingChoice("windows-1252", "Windows-1252"),
        EncodingChoice("ISO-8859-1", "ISO-8859-1"),
        EncodingChoice("ISO-8859-15", "ISO-8859-15"),
        EncodingChoice("windows-1250", "Windows-1250"),
        EncodingChoice("ISO-8859-2", "ISO-8859-2"),
        EncodingChoice("windows-1251", "Cyrillic Windows-1251"),
        EncodingChoice("KOI8-R", "Cyrillic KOI8-R"),
        EncodingChoice("windows-1253", "Greek"),
        EncodingChoice("windows-1254", "Turkish"),
        EncodingChoice("windows-1255", "Hebrew"),
        EncodingChoice("windows-1256", "Arabic"),
        EncodingChoice("Shift_JIS", "Japanese Shift_JIS"),
        EncodingChoice("EUC-JP", "Japanese EUC-JP"),
        EncodingChoice("GB18030", "Chinese Simplified"),
        EncodingChoice("Big5", "Chinese Traditional"),
        EncodingChoice("EUC-KR", "Korean"),
    ).filter { charsetOrNull(it.charsetName) != null }

    fun charsetOrNull(name: String): Charset? = try {
        Charset.forName(name)
    } catch (_: IllegalArgumentException) {
        // Illegal and unsupported names both extend it: a name from a stale setting or a detector.
        null
    }

    /** The BOM at the head of [bytes], if any; the four-byte UTF-32 marks are tested before UTF-16's. */
    fun byteOrderMark(bytes: ByteArray, length: Int): TextEncoding? = when {
        bytes.startsWith(length, 0xEF, 0xBB, 0xBF) -> TextEncoding(Charsets.UTF_8, 3, EncodingSource.BOM)
        bytes.startsWith(length, 0xFF, 0xFE, 0x00, 0x00) -> TextEncoding(UTF_32LE, 4, EncodingSource.BOM)
        bytes.startsWith(length, 0x00, 0x00, 0xFE, 0xFF) -> TextEncoding(UTF_32BE, 4, EncodingSource.BOM)
        bytes.startsWith(length, 0xFF, 0xFE) -> TextEncoding(Charsets.UTF_16LE, 2, EncodingSource.BOM)
        bytes.startsWith(length, 0xFE, 0xFF) -> TextEncoding(Charsets.UTF_16BE, 2, EncodingSource.BOM)
        else -> null
    }

    /**
     * Bytes to skip when [charset] was chosen by hand: the BOM only if it is this charset's own, so
     * picking Windows-1252 for a UTF-8 file shows the stray characters rather than hiding them.
     */
    fun bomLengthFor(charset: Charset, bytes: ByteArray, length: Int): Int = when (charset) {
        Charsets.UTF_8 -> if (bytes.startsWith(length, 0xEF, 0xBB, 0xBF)) 3 else 0
        Charsets.UTF_16LE -> if (bytes.startsWith(length, 0xFF, 0xFE)) 2 else 0
        Charsets.UTF_16BE -> if (bytes.startsWith(length, 0xFE, 0xFF)) 2 else 0
        UTF_32LE -> if (bytes.startsWith(length, 0xFF, 0xFE, 0x00, 0x00)) 4 else 0
        UTF_32BE -> if (bytes.startsWith(length, 0x00, 0x00, 0xFE, 0xFF)) 4 else 0
        else -> 0
    }

    /**
     * [truncated] marks the sample as the head of a longer file, so a cut-off multi-byte sequence is not
     * evidence against UTF-8. [guesser] is asked only about bytes that are not UTF-8.
     */
    fun detect(
        sample: ByteArray,
        length: Int = sample.size,
        truncated: Boolean = false,
        guesser: CharsetGuesser? = null,
    ): TextEncoding {
        byteOrderMark(sample, length)?.let { return it }

        var ascii = true
        var hasNul = false
        for (i in 0 until length) {
            val b = sample[i].toInt()
            if (b < 0) ascii = false else if (b == 0) hasNul = true
        }
        if (hasNul) guessUtf16WithoutBom(sample, length)?.let { return TextEncoding(it, 0, EncodingSource.UTF16_PATTERN) }
        if (ascii) return TextEncoding(Charsets.UTF_8, 0, EncodingSource.ASCII)
        if (isValidUtf8(sample, length, truncated)) return TextEncoding(Charsets.UTF_8, 0, EncodingSource.UTF8)

        resolveGuess(sample, length, guesser)?.let { return TextEncoding(it, 0, EncodingSource.GUESSED) }
        return TextEncoding(WINDOWS_1252, 0, EncodingSource.FALLBACK)
    }

    /** Strict: overlong forms, surrogates and anything past U+10FFFF are rejected, as a real decoder does. */
    fun isValidUtf8(bytes: ByteArray, length: Int, truncated: Boolean = false): Boolean {
        var i = 0
        while (i < length) {
            val lead = bytes[i].toInt() and 0xFF
            if (lead < 0x80) {
                i++
                continue
            }
            // The first continuation byte has a narrower range after some leads; the rest are always 80..BF.
            var low = 0x80
            var high = 0xBF
            val extra = when (lead) {
                in 0xC2..0xDF -> 1
                0xE0 -> { low = 0xA0; 2 }
                0xED -> { high = 0x9F; 2 }
                in 0xE1..0xEF -> 2
                0xF0 -> { low = 0x90; 3 }
                0xF4 -> { high = 0x8F; 3 }
                in 0xF1..0xF3 -> 3
                else -> return false
            }
            for (k in 1..extra) {
                // Cut off by the end of the sample: fine only if the file goes on past it.
                if (i + k >= length) return truncated
                val b = bytes[i + k].toInt() and 0xFF
                if (k == 1) {
                    if (b < low || b > high) return false
                } else if (b < 0x80 || b > 0xBF) {
                    return false
                }
            }
            i += extra + 1
        }
        return true
    }

    /**
     * Latin text in UTF-16 has a zero in the high byte of nearly every unit, so zeros pile up on one side of the pairs.
     */
    private fun guessUtf16WithoutBom(bytes: ByteArray, length: Int): Charset? {
        val pairs = length / 2
        if (pairs < 2) return null
        var evenZeros = 0
        var oddZeros = 0
        for (p in 0 until pairs) {
            if (bytes[2 * p].toInt() == 0) evenZeros++
            if (bytes[2 * p + 1].toInt() == 0) oddZeros++
        }
        val floor = pairs * UTF16_ZERO_SHARE_PERCENT / 100
        return when {
            oddZeros >= floor && oddZeros > 0 && evenZeros * UTF16_LOPSIDED_FACTOR <= oddZeros -> Charsets.UTF_16LE
            evenZeros >= floor && evenZeros > 0 && oddZeros * UTF16_LOPSIDED_FACTOR <= evenZeros -> Charsets.UTF_16BE
            else -> null
        }
    }

    private fun resolveGuess(sample: ByteArray, length: Int, guesser: CharsetGuesser?): Charset? {
        val name = guesser?.guess(sample, length) ?: return null
        val charset = charsetOrNull(name) ?: return null
        // ISO-8859-1 is what detectors say for Windows-1252 text without a curly quote in the sample;
        // Windows-1252 decodes the same bytes plus the 0x80-0x9F range, where Latin-1 has control codes.
        if (charset.name().equals("ISO-8859-1", ignoreCase = true)) return WINDOWS_1252
        // Unicode forms were ruled out above, and a charset that is not ASCII-compatible (EBCDIC)
        // would turn ordinary text into noise: both are a misfire, not an answer.
        if (charset.name().startsWith("UTF-", ignoreCase = true) || !isAsciiCompatible(charset)) return null
        return charset
    }

    private fun isAsciiCompatible(charset: Charset): Boolean {
        val probe = "\n A~09z"
        return probe.toByteArray(charset).contentEquals(probe.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArray.startsWith(length: Int, vararg prefix: Int): Boolean =
        length >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }
}
