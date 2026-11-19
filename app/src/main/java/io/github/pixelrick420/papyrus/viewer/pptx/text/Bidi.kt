package io.github.pixelrick420.papyrus.viewer.pptx.text

import java.text.Bidi as JavaBidi

/** A directional segment; [level] is even for left-to-right and odd for right-to-left. */
internal class BidiRun(val start: Int, val end: Int, val level: Int) {

    val rtl: Boolean get() = level % 2 == 1
}

/** The Unicode bidi algorithm, split into the runs each shaping call measures (plan §4.7 step 3). */
internal object Bidi {

    // java.text.Bidi names these constants differently on the JVM and on Android; the values are the same.
    private const val DIRECTION_LTR = 0

    private const val DIRECTION_RTL = 1

    fun runs(text: CharSequence, baseRtl: Boolean): List<BidiRun> {
        if (text.isEmpty()) return emptyList()
        val bidi = JavaBidi(text.toString(), if (baseRtl) DIRECTION_RTL else DIRECTION_LTR)
        return (0 until bidi.runCount).map {
            BidiRun(bidi.getRunStart(it), bidi.getRunLimit(it), bidi.getRunLevel(it))
        }
    }
}
