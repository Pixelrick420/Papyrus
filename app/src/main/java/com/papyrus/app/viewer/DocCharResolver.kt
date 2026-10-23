package com.papyrus.app.viewer

/**
 * Turns a run's direct character sprms into the [CharFormat] it draws with, by layering what Word
 * layers: the paragraph's style, then a character style if the run names one, then the sprms. A run
 * says what it is by its bytes and its paragraph's style alone, so results are cached by both.
 */
internal class DocCharResolver(private val styles: DocStyleSheet, private val fonts: DocFontTable) {

    private class Key(val istd: Int, val sprms: ByteArray) {
        override fun equals(other: Any?): Boolean = other is Key && istd == other.istd && sprms.contentEquals(other.sprms)

        override fun hashCode(): Int = 31 * istd + sprms.contentHashCode()
    }

    private val paragraphBases = HashMap<Int, RunProps>()
    private val formats = HashMap<Key, CharFormat>()
    private val plain = HashMap<Int, CharFormat>()

    /** The format of a run in a paragraph of style [paraIstd] with direct sprms `page[from, end)`; none if [page] is null. */
    fun format(paraIstd: Int, page: ByteArray?, from: Int, end: Int): CharFormat {
        if (page == null || end <= from) return plain.getOrPut(paraIstd) { copyOf(paragraphBase(paraIstd)).toFormat(null) }
        if (formats.size >= MAX_CACHED) formats.clear()
        val key = Key(paraIstd, page.copyOfRange(from, end))
        return formats.getOrPut(key) { resolve(paraIstd, key.sprms) }
    }

    private fun resolve(paraIstd: Int, sprms: ByteArray): CharFormat {
        val props = copyOf(paragraphBase(paraIstd))
        val scan = DocCharProps.scan(sprms, 0, sprms.size)
        // sprmCPlain drops the character style; the sprms after it still apply.
        if (scan.styleIstd > 0 && !scan.plain) {
            for (layer in styles.charSprms(scan.styleIstd)) DocCharProps.apply(props, layer, 0, layer.size, fonts)
        }
        DocCharProps.apply(props, sprms, 0, sprms.size, fonts)
        return props.toFormat(null)
    }

    /** What the paragraph style gives its text before any run says anything. */
    private fun paragraphBase(paraIstd: Int): RunProps {
        val istd = if (styles.has(paraIstd)) paraIstd else 0
        return paragraphBases.getOrPut(istd) {
            val props = RunProps()
            for (layer in styles.charSprms(istd)) DocCharProps.apply(props, layer, 0, layer.size, fonts)
            props
        }
    }

    /** A layer that may be changed without changing the cached one. */
    private fun copyOf(base: RunProps): RunProps = base.mergedOver(RunProps())

    private companion object {
        const val MAX_CACHED = 4096
    }
}
