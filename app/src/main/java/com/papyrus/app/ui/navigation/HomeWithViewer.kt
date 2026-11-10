package com.papyrus.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.papyrus.app.R
import com.papyrus.app.ui.AppViewModelProvider
import com.papyrus.app.ui.screens.HomeScreen
import com.papyrus.app.ui.screens.HomeViewModel

// Width alone is not enough: a phone turned sideways is as wide as a small tablet but only a few hundred dp high, and keeps its single pane.
internal fun showsListAndViewer(width: WindowWidthSizeClass, height: WindowHeightSizeClass): Boolean =
    width >= WindowWidthSizeClass.Medium && height != WindowHeightSizeClass.Compact

internal fun WindowSizeClass.showsListAndViewer(): Boolean = showsListAndViewer(widthSizeClass, heightSizeClass)

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
internal fun rememberTwoPane(): Boolean {
    val activity = LocalActivity.current ?: return false
    return calculateWindowSizeClass(activity).showsListAndViewer()
}

private const val PANE_EMPTY = "pane_empty"

private const val LIST_PANE_SHARE = 0.4f
private val ListPaneMinWidth = 280.dp
private val ListPaneMaxWidth = 400.dp

internal fun listPaneWidth(available: Dp): Dp = (available * LIST_PANE_SHARE).coerceIn(ListPaneMinWidth, ListPaneMaxWidth)

// The pane is a navigation host of its own so each document gets its own arguments, saved state and a view model cleared when another replaces it.
@Composable
internal fun HomeWithViewer(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Created here and handed to the list, so this and the list agree on one library state.
    val home: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory)
    val state by home.uiState.collectAsStateWithLifecycle()
    val paneNav = rememberNavController()
    val paneEntry by paneNav.currentBackStackEntryAsState()
    val openId: Long? = paneEntry
        ?.takeIf { it.destination.route == Routes.VIEWER }
        ?.arguments
        ?.getLong(Routes.ARG_DOCUMENT_ID)

    val closeDocument: () -> Unit = { paneNav.popBackStack(PANE_EMPTY, inclusive = false) }
    val openDocument: (Long, String?) -> Unit = { id, query ->
        // The same row again, with no new search word, would only reload what is already on screen.
        if (id != openId || query != null) {
            paneNav.navigate(Routes.viewer(id, query)) {
                // Back to the empty pane first, so the document shown before leaves the stack.
                popUpTo(PANE_EMPTY)
            }
        }
    }

    // A document removed from the library, its undo window included, closes with its row. Only a
    // library row can go: a handed-over document has a negative id and was never in the list.
    LaunchedEffect(openId, state.loaded, state.documents) {
        val id = openId ?: return@LaunchedEffect
        if (id > 0 && state.loaded && state.documents.none { it.id == id }) closeDocument()
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        // Read here: inside the Row below, that layout scope is the implicit receiver and this one is not.
        val paneWidth = listPaneWidth(maxWidth)
        Row(
            Modifier
                .fillMaxSize()
                // Taken once for the pair, so each pane's Scaffold does not pad the edge it shares
                // with the other: only the outer sides meet the system bars and a cutout.
                .windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
                ),
        ) {
            Box(Modifier.width(paneWidth).fillMaxHeight()) {
                HomeScreen(
                    onOpenDocument = openDocument,
                    onOpenSettings = onOpenSettings,
                    openDocumentId = openId,
                    viewModel = home,
                )
            }
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                NavHost(
                    navController = paneNav,
                    startDestination = PANE_EMPTY,
                    enterTransition = { fadeIn(tween(PANE_FADE_MS)) },
                    exitTransition = { ExitTransition.None },
                    popEnterTransition = { EnterTransition.None },
                    popExitTransition = { fadeOut(tween(PANE_FADE_MS)) },
                ) {
                    composable(PANE_EMPTY) { PanePlaceholder() }
                    viewerDestination(onBack = closeDocument)
                }
            }
        }
    }

    // nav-compose registers its own back handler after the first frame and only the last enabled handler
    // is asked, so this re-registers whenever the pane changes and lands after that one.
    key(openId, state.selection.active) {
        BackHandler(enabled = state.selection.active || openId != null) {
            if (state.selection.active) home.exitSelection() else closeDocument()
        }
    }
}

private const val PANE_FADE_MS = 150

@Composable
internal fun PanePlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.home_pane_placeholder),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}
