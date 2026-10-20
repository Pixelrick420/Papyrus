package com.papyrus.app.ui.viewer

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.papyrus.app.tts.TtsAlignment
import com.papyrus.app.tts.TtsProgress
import com.papyrus.app.viewer.NormRect
import com.papyrus.app.viewer.PdfPageText

/*
 * Read aloud, as the viewers draw it: the sentence being read is shaded, the word being spoken is
 * shaded harder, and with the lock on the page scrolls to keep that line on screen. Highlighting never
 * depends on the lock; only the scrolling does.
 */

/**
 * The unit being read aloud, as a viewer sees it: [anchor] is where in the document (the page, chunk or
 * block it is in, or the Markdown character offset it starts at) and [progress] where in the unit the
 * voice is.
 */
@Immutable
class TtsHighlight(val anchor: Int, val progress: TtsProgress)

/**
 * What of one text is being read aloud, in that text's own offsets: the [sentence] and, inside it, the
 * [word]. With [follow], the page scrolls to keep the line on screen, measuring against the viewport
 * [viewportHeight] reports.
 */
@Immutable
internal class SpokenRange(
    val sentence: IntRange?,
    val word: IntRange?,
    val follow: Boolean,
    val viewportHeight: () -> Int,
)

/**
 * This highlight's sentence and word as found in [source], the text the unit was cut from. Before the
 * engine reports a word the whole unit is the sentence. Null when none of it lies in [source].
 */
internal fun TtsHighlight.rangesIn(source: CharSequence, follow: Boolean, viewportHeight: () -> Int): SpokenRange? {
    val text = progress.text
    if (text.isEmpty()) return null
    val sentence = TtsAlignment.sourceRange(source, text, progress.start, progress.sentence ?: text.indices)
    val word = progress.word?.let { TtsAlignment.sourceRange(source, text, progress.start, it) }
    return if (sentence == null && word == null) null else SpokenRange(sentence, word, follow, viewportHeight)
}

/** [rangesIn] for a composable: found again only when the voice or the text moves. */
@Composable
internal fun rememberSpokenRange(
    speaking: TtsHighlight?,
    source: CharSequence?,
    follow: Boolean,
    viewportHeight: () -> Int,
): SpokenRange? = remember(speaking, source, follow) {
    if (speaking == null || source == null) null else speaking.rangesIn(source, follow, viewportHeight)
}

/** The one accent at two strengths, so it reads on a page and on the surface in either theme. */
internal const val SPOKEN_SENTENCE_ALPHA = 0.16f
internal const val SPOKEN_WORD_ALPHA = 0.4f

@Composable
internal fun spokenSentenceColor(): Color = MaterialTheme.colorScheme.primary.copy(alpha = SPOKEN_SENTENCE_ALPHA)

@Composable
internal fun spokenWordColor(): Color = MaterialTheme.colorScheme.primary.copy(alpha = SPOKEN_WORD_ALPHA)

/** Room kept above the line being read, so it never sits against the top edge. */
internal val FollowTopMargin = 24.dp

/**
 * A block that has no text to mark, a table or an image, shaded as a whole while it is being read and
 * followed by its top. Always a box, so the block inside is not rebuilt when the shading comes and goes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SpokenBlock(
    active: Boolean,
    follow: Boolean,
    viewportHeight: () -> Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val requester = remember { BringIntoViewRequester() }
    val topMargin = with(LocalDensity.current) { FollowTopMargin.toPx() }
    LaunchedEffect(active, follow) {
        if (!active || !follow) return@LaunchedEffect
        val window = followWindow(lineHeight = 0f, viewport = viewportHeight().toFloat(), topMargin = topMargin)
        requester.bringIntoView(Rect(0f, -window.above, 1f, window.below))
    }
    val shade = if (active) Modifier.background(spokenSentenceColor(), RoundedCornerShape(4.dp)) else Modifier
    Box(modifier.bringIntoViewRequester(requester).then(shade)) { content() }
}

/** The sentence and word being read on one PDF page, as page-relative rectangles. */
internal class SpokenRects(val sentence: List<NormRect>, val word: List<NormRect>)

/** Where the words of [this] sit on [page], whose text it was found in; empty without glyph boxes. */
internal fun SpokenRange.rectsOn(page: PdfPageText): SpokenRects = SpokenRects(
    sentence = sentence?.let { page.selectionRects(it.first, it.last + 1) }.orEmpty(),
    word = word?.let { page.selectionRects(it.first, it.last + 1) }.orEmpty(),
)

/** Fills page-relative [rects] across the whole of the canvas being drawn on. */
internal fun DrawScope.drawSpokenRects(rects: List<NormRect>, color: Color, corner: Float) {
    for (rect in rects) {
        drawRoundRect(
            color = color,
            topLeft = Offset(rect.left * size.width, rect.top * size.height),
            size = Size((rect.right - rect.left) * size.width, (rect.bottom - rect.top) * size.height),
            cornerRadius = CornerRadius(corner),
        )
    }
}
