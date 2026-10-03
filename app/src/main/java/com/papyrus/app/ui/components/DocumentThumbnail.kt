package com.papyrus.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.papyrus.app.R
import com.papyrus.app.data.DocumentEntity
import com.papyrus.app.data.ThumbnailLoader
import com.papyrus.app.data.ThumbnailResult

/** Fixed-size leading slot: claiming the row width would starve the row's weighted title column down to zero. */
@Composable
fun DocumentThumbnail(
    document: DocumentEntity,
    loader: ThumbnailLoader,
    modifier: Modifier = Modifier,
) {
    val cached = loader.cached(document)
    val result by produceState<ThumbnailResult?>(initialValue = cached?.let(ThumbnailResult::Image), document, cached) {
        if (cached == null) value = loader.load(document)
    }

    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .size(TILE_SIZE)
            .clip(shape)
            // surfaceVariant, never white: a document page is mostly white, which made loaded, loading and failed indistinguishable.
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // Hairline for the same reason: a white page on a near-white card has no edge otherwise.
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), shape),
        contentAlignment = Alignment.Center,
    ) {
        // A cached bitmap is the initial state, so it shows at once; only a tile that was still
        // loading cross-fades from its label to the page, rather than popping in mid-scroll.
        Crossfade(
            targetState = result,
            modifier = Modifier.fillMaxSize(),
            animationSpec = tween(250),
            label = "thumbnail",
        ) { shown ->
            // Crossfade lays its content out top-start, so each state centres itself.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val bitmap = (shown as? ThumbnailResult.Image)?.bitmap
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        // Decorative: the title beside it already names the document, so a screen reader would hear it twice.
                        contentDescription = null,
                        // Crop, top-anchored: a centred crop slices off the first lines, and those identify
                        // the document (a text tile's first line sits only ~6px down).
                        contentScale = ContentScale.Crop,
                        alignment = Alignment.TopCenter,
                        modifier = Modifier.fillMaxSize().clearAndSetSemantics { },
                    )
                } else {
                    Text(
                        text = if (shown is ThumbnailResult.NoAccess) {
                            stringResource(R.string.home_no_access)
                        } else {
                            document.format.label
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 4.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private val TILE_SIZE = 64.dp
