package com.papyrus.app.ui.navigation

import android.net.Uri
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
import com.papyrus.app.ui.screens.ScannerScreen
import com.papyrus.app.ui.screens.ViewerScreen

object Routes {
    const val HOME = "home"
    const val SCANNER = "scanner"
    const val ARG_DOCUMENT_ID = "documentId"
    const val VIEWER = "viewer/{$ARG_DOCUMENT_ID}"

    // Only the Room row id travels through navigation, which avoids URI-encoding pitfalls in route strings.
    fun viewer(documentId: Long) = "viewer/$documentId"
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
    LaunchedEffect(externalDocument) {
        val uri = externalDocument ?: return@LaunchedEffect
        val id = resolveExternalDocument(uri)
        onExternalDocumentHandled()
        if (id != null) navController.navigate(Routes.viewer(id)) { launchSingleTop = true }
    }

    // Stacked rather than cross-faded: only the screen on top moves, sliding a short way in while it
    // fades up, and the one underneath stays put and opaque. The default is a 700 ms fade of both,
    // which is slow to open a file and shows the window background through the middle of it.
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
            // Still registered for hardware scanners and share intents, though no UI navigates here.
            HomeScreen(onOpenDocument = { id -> navController.navigate(Routes.viewer(id)) })
        }
        composable(Routes.SCANNER) {
            ScannerScreen(
                onBack = { navController.popBackStack() },
                onDocumentSaved = { id ->
                    navController.navigate(Routes.viewer(id)) { popUpTo(Routes.HOME) }
                },
            )
        }
        composable(
            route = Routes.VIEWER,
            arguments = listOf(navArgument(Routes.ARG_DOCUMENT_ID) { type = NavType.LongType }),
        ) {
            ViewerScreen(onBack = { navController.popBackStack() })
        }
    }
}
