package com.papyrus.app.viewer

/**
 * One level of a list: its number format [nfc], where counting starts, and [text], the template the
 * marker is made from. In [text] a character 0 to 8 stands for the number of that level and any
 * other is literal, so `[0, '.']` is "1." and a bullet is the one bullet character.
 */
internal class DocListLevel(val nfc: Int, val start: Int, val text: String)

/**
 * The list definitions (PlfLst, with its LVLs) and the list instances that paragraphs name (PlfLfo),
 * both from the table stream. A paragraph carries `ilfo`, a 1-based index into the instances; an
 * instance names a list by `lsid` and may restart a level or replace its formatting.
 *
 * PlfLst is `cLst`, then `cLst` LSTFs of 28 bytes (an `lsid`, and a flag whose low bit says the list has
 * one level rather than nine); the LVLs for every list follow in order, outside the PlfLst's own length.
 * An LVL is a 28-byte LVLF, its paragraph and character grpprls, then the length-prefixed number text.
 * PlfLfo is `lfoMac`, that many 16-byte LFOs, then per LFO a `cp` and its `clfolvl` LFOLVLs: a start
 * number, flags, and when the flags ask for it a whole LVL.
 *
 * Like the style sheet this is enrichment: anything unreadable leaves the paragraphs unnumbered.
 */
internal class DocListTables private constructor(
    private val lists: List<Array<DocListLevel?>>,
    private val instances: List<Instance>,
) {
    /** An LFO: the index in [lists] of the list it uses, level restarts, and replaced levels. */
    private class Instance(val list: Int, val startAt: Map<Int, Int>, val levels: Map<Int, DocListLevel>)

    /** Index of the list instance [ilfo] uses, or null if there is no such instance or list. */
    fun listOf(ilfo: Int): Int? = instances.getOrNull(ilfo - 1)?.list

    /** Level [level] of instance [ilfo]: the instance's own formatting if it replaces the list's. */
    fun level(ilfo: Int, level: Int): DocListLevel? {
        val instance = instances.getOrNull(ilfo - 1) ?: return null
        return instance.levels[level] ?: lists.getOrNull(instance.list)?.getOrNull(level)
    }

    /** Levels instance [ilfo] restarts, by the number the first paragraph shows. */
    fun startOverrides(ilfo: Int): Map<Int, Int> = instances.getOrNull(ilfo - 1)?.startAt ?: emptyMap()

    companion object {
        /** FibRgFcLcb97 indexes of fcPlfLst and fcPlfLfo. */
        const val PAIR_PLFLST = 73
        const val PAIR_PLFLFO = 74

        /** Levels in a list that is not simple. */
        const val LEVELS = 9

        val EMPTY = DocListTables(emptyList(), emptyList())

        private const val MAX_LIST_BYTES = 4 * 1024 * 1024
        private const val LSTF_BYTES = 28
        private const val LFO_BYTES = 16
        private const val LFOLVL_BYTES = 8
        private const val LVLF_BYTES = 28
        private const val MAX_LISTS = 4096
        private const val MAX_INSTANCES = 4096
        private const val MAX_NUMBER_TEXT = 255
        private const val FLAG_SIMPLE_LIST = 0x01
        private const val FLAG_START_AT = 0x10
        private const val FLAG_FORMATTING = 0x20
        private const val ILVL_MASK = 0x0F

        /** Reads both tables, or returns [EMPTY] if either is absent or unreadable. */
        fun parse(cfb: CompoundFile, fib: DocFib): DocListTables {
            val lstLcb = fib.lcb(PAIR_PLFLST)
            val lfoLcb = fib.lcb(PAIR_PLFLFO)
            if (lstLcb < 2 || lfoLcb < 4 || lfoLcb > MAX_LIST_BYTES) return EMPTY
            val size = cfb.streamSize(fib.tableStream) ?: return EMPTY
            val lstFc = fib.fc(PAIR_PLFLST)
            val lfoFc = fib.fc(PAIR_PLFLFO)
            if (lstFc >= size || lfoFc >= size) return EMPTY
            // The LVLs run on past the PlfLst's recorded length, so read what may hold them.
            val lst = cfb.readOrNull(fib.tableStream, lstFc, minOf(size - lstFc, MAX_LIST_BYTES.toLong()).toInt()) ?: return EMPTY
            val lfo = cfb.readOrNull(fib.tableStream, lfoFc, minOf(size - lfoFc, lfoLcb).toInt()) ?: return EMPTY
            return parse(lst, lstLcb.toInt().coerceAtMost(lst.size), lfo)
        }

        /** Parses a PlfLst of [lstLcb] bytes at the start of [lst] (LVLs after it) and a PlfLfo in [lfo]. */
        fun parse(lst: ByteArray, lstLcb: Int, lfo: ByteArray): DocListTables {
            if (lst.size < 2 || lfo.size < 4) return EMPTY
            val count = u16(lst, 0).coerceAtMost(MAX_LISTS)
            if (2 + count * LSTF_BYTES > lstLcb) return EMPTY
            val ids = HashMap<Long, Int>()
            val lists = ArrayList<Array<DocListLevel?>>(count)
            var pos = 2 + count * LSTF_BYTES
            for (i in 0 until count) {
                val at = 2 + i * LSTF_BYTES
                ids.putIfAbsent(u32(lst, at), i)
                val simple = lst[at + LSTF_BYTES - 2].toInt() and FLAG_SIMPLE_LIST != 0
                val levels = arrayOfNulls<DocListLevel>(if (simple) 1 else LEVELS)
                for (l in levels.indices) {
                    val parsed = readLevel(lst, pos) ?: break
                    levels[l] = parsed.first
                    pos = parsed.second
                }
                lists += levels
            }
            return DocListTables(lists, readInstances(lfo, ids))
        }

        private fun readInstances(lfo: ByteArray, ids: Map<Long, Int>): List<Instance> {
            val count = u32(lfo, 0).coerceAtMost(MAX_INSTANCES.toLong()).toInt()
            if (4 + count * LFO_BYTES > lfo.size) return emptyList()
            val out = ArrayList<Instance>(count)
            var pos = 4 + count * LFO_BYTES
            for (i in 0 until count) {
                val at = 4 + i * LFO_BYTES
                val overrides = lfo[at + LFO_CLFOLVL].toInt() and 0xFF
                // Each LFO's data opens with a 4-byte `cp`, then its LFOLVLs.
                pos += 4
                val startAt = HashMap<Int, Int>()
                val levels = HashMap<Int, DocListLevel>()
                for (k in 0 until overrides) {
                    if (pos + LFOLVL_BYTES > lfo.size) break
                    val flags = u32(lfo, pos + 4).toInt()
                    val level = flags and ILVL_MASK
                    val iStart = i32(lfo, pos)
                    pos += LFOLVL_BYTES
                    if (flags and FLAG_START_AT != 0) startAt[level] = iStart
                    if (flags and FLAG_FORMATTING != 0) {
                        val parsed = readLevel(lfo, pos) ?: break
                        val own = parsed.first
                        levels[level] = if (flags and FLAG_START_AT != 0) DocListLevel(own.nfc, iStart, own.text) else own
                        pos = parsed.second
                    }
                }
                val list = ids[u32(lfo, at)]
                // An instance of a list that is not defined keeps its place, so later `ilfo`s still line up.
                out += Instance(list ?: -1, startAt, levels)
            }
            return out
        }

        /** An LVL at [at] in [data] and where it ends, or null if it does not fit. */
        private fun readLevel(data: ByteArray, at: Int): Pair<DocListLevel, Int>? {
            if (at < 0 || at + LVLF_BYTES > data.size) return null
            val start = i32(data, at)
            val nfc = data[at + LVLF_NFC].toInt() and 0xFF
            val papxBytes = data[at + LVLF_CB_PAPX].toInt() and 0xFF
            val chpxBytes = data[at + LVLF_CB_CHPX].toInt() and 0xFF
            val textAt = at + LVLF_BYTES + papxBytes + chpxBytes
            if (textAt + 2 > data.size) return null
            val chars = u16(data, textAt)
            if (chars > MAX_NUMBER_TEXT || textAt + 2 + chars * 2 > data.size) return null
            val text = String(data, textAt + 2, chars * 2, Charsets.UTF_16LE)
            return DocListLevel(nfc, start, text) to (textAt + 2 + chars * 2)
        }

        private const val LVLF_NFC = 4
        private const val LVLF_CB_CHPX = 24
        private const val LVLF_CB_PAPX = 25
        private const val LFO_CLFOLVL = 12
    }
}

/**
 * Numbers list paragraphs in reading order, the way Word does: a list's counters are shared by every
 * instance of it, the first paragraph of an instance applies its restarts, and a new item restarts
 * every level below it. Mirrors `ListCounters` for `.docx`.
 */
internal class DocListCounters(private val tables: DocListTables) {
    private val counts = HashMap<Int, IntArray>()
    private val started = HashSet<Int>()

    fun defines(ilfo: Int): Boolean = (tables.listOf(ilfo) ?: -1) >= 0

    /** The marker for the next paragraph of instance [ilfo] at [ilvl]; null if the level is not defined. */
    fun next(ilfo: Int, ilvl: Int): String? {
        val list = tables.listOf(ilfo)?.takeIf { it >= 0 } ?: return null
        val level = ilvl.coerceIn(0, DocListTables.LEVELS - 1)
        val definition = tables.level(ilfo, level) ?: return null
        val values = counts.getOrPut(list) { IntArray(DocListTables.LEVELS) { UNSET } }
        if (started.add(ilfo)) {
            tables.startOverrides(ilfo).forEach { (overridden, startAt) ->
                if (overridden in values.indices) values[overridden] = startAt - 1
            }
        }
        values[level] = if (values[level] == UNSET) definition.start else values[level] + 1
        // A new item at this level starts every deeper level over.
        for (deeper in level + 1 until values.size) values[deeper] = UNSET
        return marker(ilfo, definition, level, values)
    }

    private fun marker(ilfo: Int, definition: DocListLevel, level: Int, values: IntArray): String {
        if (definition.nfc == NFC_BULLET) return displayBullet(definition.text, level)
        if (definition.nfc == NFC_NONE) return ""
        val out = StringBuilder()
        for (c in definition.text) {
            if (c.code in values.indices) {
                val referenced = tables.level(ilfo, c.code)
                val value = if (values[c.code] == UNSET) referenced?.start ?: 1 else values[c.code]
                out.append(formatListNumber(value, formatOf(referenced?.nfc)))
            } else {
                out.append(c)
            }
        }
        return out.toString()
    }

    /** The OOXML `numFmt` name [formatListNumber] takes for a binary `nfc`; formats it lacks count as decimal. */
    private fun formatOf(nfc: Int?): String = when (nfc) {
        NFC_UPPER_ROMAN -> "upperRoman"
        NFC_LOWER_ROMAN -> "lowerRoman"
        NFC_UPPER_LETTER -> "upperLetter"
        NFC_LOWER_LETTER -> "lowerLetter"
        NFC_DECIMAL_ZERO -> "decimalZero"
        else -> "decimal"
    }

    private companion object {
        const val UNSET = Int.MIN_VALUE
        const val NFC_UPPER_ROMAN = 1
        const val NFC_LOWER_ROMAN = 2
        const val NFC_UPPER_LETTER = 3
        const val NFC_LOWER_LETTER = 4
        const val NFC_DECIMAL_ZERO = 22
        const val NFC_BULLET = 0x17
        const val NFC_NONE = 0xFF
    }
}
