package io.github.pixelrick420.papyrus.tts

// Pure: one utterance's text is kept below the platform's documented 4000-character input default. 3500 leaves
// headroom for engines that count differently, so the cap holds even where the platform value is not queried.
internal object TtsUnitSplitter {
    const val MAX_UNIT_CHARS = 3_500

    /** Ranges into [text], in order, that together cover it without gaps. */
    fun split(text: String, max: Int = MAX_UNIT_CHARS): List<IntRange> {
        if (text.length <= max) return listOf(0 until text.length)
        val result = ArrayList<IntRange>()
        val bounds = TtsAlignment.sentenceBounds(text)
        var from = 0
        while (from < text.length) {
            if (text.length - from <= max) {
                result.add(from until text.length)
                break
            }
            val to = endOf(text, from, max, bounds)
            result.add(from until to)
            from = to
        }
        return result
    }

    private fun endOf(text: String, from: Int, max: Int, bounds: IntArray): Int {
        var i = 0
        while (i < bounds.size && bounds[i] <= from) i++
        var to = from
        while (i < bounds.size && bounds[i] - from <= max) {
            to = bounds[i]
            i++
        }
        if (to > from) return to

        val limit = from + max
        for (p in limit - 1 downTo from + 1) {
            if (text[p].isWhitespace()) return p + 1
        }

        var cut = limit
        if (Character.isHighSurrogate(text[cut - 1]) && Character.isLowSurrogate(text[cut])) cut--
        if (cut <= from) cut = from + 1
        return cut
    }
}
