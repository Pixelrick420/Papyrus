package io.github.pixelrick420.papyrus.ui.viewer

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
import io.github.pixelrick420.papyrus.tts.TtsAlignment
import io.github.pixelrick420.papyrus.tts.TtsProgress
import io.github.pixelrick420.papyrus.viewer.NormRect
import io.github.pixelrick420.papyrus.viewer.PdfPageText

@Immutable
class TtsHighlight(val anchor: Int, val progress: TtsProgress)

@Immutable
internal class SpokenRange(
    val sentence: IntRange?,
    val word: IntRange?,
    val follow: Boolean,
    val viewportHeight: () -> Int,
)

// Before the engine reports a word the whole unit is the sentence.
internal fun TtsHighlight.rangesIn(source: CharSequence, follow: Boolean, viewportHeight: () -> Int): SpokenRange? {
    val text = progress.text
    if (text.isEmpty()) return null
    val sentence = TtsAlignment.sourceRange(source, text, progress.start, progress.sentence ?: text.indices)
    val word = progress.word?.let { TtsAlignment.sourceRange(source, text, progress.start, it) }
    return if (sentence == null && word == null) null else SpokenRange(sentence, word, follow, viewportHeight)
}

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

internal val FollowTopMargin = 24.dp

// Always a box, so the block inside is not rebuilt when the shading comes and goes.
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

internal class SpokenRects(val sentence: List<NormRect>, val word: List<NormRect>)

internal fun SpokenRange.rectsOn(page: PdfPageText): SpokenRects = SpokenRects(
    sentence = sentence?.let { page.selectionRects(it.first, it.last + 1) }.orEmpty(),
    word = word?.let { page.selectionRects(it.first, it.last + 1) }.orEmpty(),
)

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
