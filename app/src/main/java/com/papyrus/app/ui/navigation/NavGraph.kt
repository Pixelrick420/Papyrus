package com.papyrus.app.ui.navigation

import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.papyrus.app.ui.screens.HomeScreen
import com.papyrus.app.ui.screens.SettingsScreen
import com.papyrus.app.ui.screens.ViewerScreen

object Routes {
    const val HOME = "home"
    const val ARG_DOCUMENT_ID = "documentId"
    const val ARG_QUERY = "query"
    const val VIEWER = "viewer/{$ARG_DOCUMENT_ID}?$ARG_QUERY={$ARG_QUERY}"
    const val SETTINGS = "settings"

    // Only the Room row id travels, plus the search word a content result should land on; the query
    // is encoded because it can hold spaces, quotes, `&` and non-ASCII.
    fun viewer(documentId: Long, query: String? = null): String =
        if (query == null) "viewer/$documentId" else "viewer/$documentId?$ARG_QUERY=${Uri.encode(query)}"
}

private const val NAV_DURATION_MS = 300

/** Material's "emphasized decelerate": fast off the mark, long soft landing. */
private val NavEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@Composable
fun PapyrusNavGraph(
    navController: NavHostController = rememberNavController(),
    externalDocument: Uri? = null,
    resolveExternalDocument: suspend (Uri) -> Long? = { null },
    onExternalDocumentHandled: () -> Unit = {},
) {
    // Back out of a handed-over document finishes Papyrus: the viewer as the only entry left lets the
    // system gesture fall through, while the toolbar arrow pops directly and needs this explicit finish().
    val activity = LocalActivity.current
    val leaveViewer: () -> Unit = { if (!navController.popBackStack()) activity?.finish() }

    LaunchedEffect(externalDocument) {
        val uri = externalDocument ?: return@LaunchedEffect
        val id = resolveExternalDocument(uri)
        onExternalDocumentHandled()
        if (id == null) return@LaunchedEffect
        // A second handover arriving while the first is still open: that viewer was not entered
        // from Home either, so it goes as well instead of becoming the place back lands.
        navController.popBackStack(Routes.VIEWER, inclusive = true)
        navController.navigate(Routes.viewer(id)) {
            // Home is popped rather than left underneath. The caller opened a document, not
            // Papyrus, so there is no Home behind this viewer to go back to.
            popUpTo(Routes.HOME) { inclusive = true }
            launchSingleTop = true
        }
    }

    // Stacked rather than cross-faded: the top screen slides and fades in over a static one. The
    // default 700 ms fade of both is slow and shows the window background through the middle.
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = {
            fadeIn(tween(NAV_DURATION_MS, easing = NavEasing)) +
                slideInHorizontally(tween(NAV_DURATION_MS, easing = NavEasing)) { it / 10 }
        },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = {
            fadeOut(tween(NAV_DURATION_MS - 50, easing = NavEasing)) +
                slideOutHorizontally(tween(NAV_DURATION_MS, easing = NavEasing)) { it / 10 }
        },
    ) {
        composable(Routes.HOME) {
            // Still registered for share intents and hardware keyboards, though no UI navigates here.
            HomeScreen(
                onOpenDocument = { id, query -> navController.navigate(Routes.viewer(id, query)) },
                // Single-top so a double tap on the icon cannot stack two Settings screens.
                onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.VIEWER,
            arguments = listOf(
                navArgument(Routes.ARG_DOCUMENT_ID) { type = NavType.LongType },
                navArgument(Routes.ARG_QUERY) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) {
            ViewerScreen(onBack = leaveViewer)
        }
    }
}
