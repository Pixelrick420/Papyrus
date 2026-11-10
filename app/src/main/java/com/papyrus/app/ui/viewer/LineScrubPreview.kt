package com.papyrus.app.ui.viewer

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.papyrus.app.R
import com.papyrus.app.viewer.lineAt
import com.papyrus.app.viewer.lineSnippet
import com.papyrus.app.viewer.locateLine
import java.text.NumberFormat

// Nothing is worked out unless [scrubbing], and then only when the line changes; no line table, so it costs a large file nothing extra.
@Composable
internal fun BoxScope.LineScrubPreview(
    listState: LazyListState,
    chunks: List<String>,
    lines: Int,
    scrubbing: Boolean,
) {
    val resources = LocalContext.current.resources
    // Kept after the knob is let go, so the readout fades out with its words and not empty.
    var shown by remember { mutableStateOf("") }
    LaunchedEffect(scrubbing, listState, chunks, lines) {
        if (!scrubbing || lines < 1) return@LaunchedEffect
        val digits = NumberFormat.getIntegerInstance()
        snapshotFlow { topLine(listState, lines) }.collect { line ->
            val target = locateLine(line, lines)
            val snippet = target?.let { lineSnippet(chunks[it.chunk], it.lineInChunk) }.orEmpty()
            val number = digits.format(line)
            shown = if (snippet.isEmpty()) {
                resources.getString(R.string.viewer_scrub_line, number)
            } else {
                resources.getString(R.string.viewer_scrub_line_text, number, snippet)
            }
        }
    }
    // Matches the scroll knob, alpha included; left of its strip, where a thumb is not covering it.
    ReadoutBadge(
        text = shown,
        visible = scrubbing && shown.isNotEmpty(),
        transformOrigin = TransformOrigin(1f, 0.5f),
        textStyle = MaterialTheme.typography.labelMedium,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        modifier = Modifier.align(Alignment.CenterEnd).padding(end = ScrollbarTouchTarget),
    )
}

private fun topLine(listState: LazyListState, lines: Int): Int {
    val first = listState.layoutInfo.visibleItemsInfo.firstOrNull() ?: return 1
    val scrolledAway = if (first.size > 0) (-first.offset).coerceAtLeast(0) / first.size.toFloat() else 0f
    return lineAt(first.index, scrolledAway, lines)
}
