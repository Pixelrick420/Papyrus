package io.github.pixelrick420.papyrus.ui.navigation

import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.ui.platform.LocalContext
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
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.github.pixelrick420.papyrus.MainApplication
import io.github.pixelrick420.papyrus.ui.screens.CrashReportsScreen
import io.github.pixelrick420.papyrus.ui.screens.HomeScreen
import io.github.pixelrick420.papyrus.ui.screens.SettingsScreen
import io.github.pixelrick420.papyrus.ui.screens.ViewerScreen

object Routes {
    const val HOME = "home"
    const val ARG_DOCUMENT_ID = "documentId"
    const val ARG_QUERY = "query"
    const val ARG_CRASH_REPORT = "crashReport"
    const val VIEWER = "viewer/{$ARG_DOCUMENT_ID}?$ARG_QUERY={$ARG_QUERY}&$ARG_CRASH_REPORT={$ARG_CRASH_REPORT}"
    const val SETTINGS = "settings"
    const val CRASH_REPORTS = "crash_reports"

    // Only the Room row id travels, plus the search word a content result should land on; the query
    // is encoded because it can hold spaces, quotes, `&` and non-ASCII.
    fun viewer(documentId: Long, query: String? = null, isCrashReport: Boolean = false): String {
        val params = mutableListOf<String>()
        if (query != null) params.add("$ARG_QUERY=${Uri.encode(query)}")
        if (isCrashReport) params.add("$ARG_CRASH_REPORT=true")
        return if (params.isEmpty()) "viewer/$documentId" else "viewer/$documentId?${params.joinToString("&")}"
    }
}

internal fun NavGraphBuilder.viewerDestination(onBack: () -> Unit) {
    composable(
        route = Routes.VIEWER,
        arguments = listOf(
            navArgument(Routes.ARG_DOCUMENT_ID) { type = NavType.LongType },
            navArgument(Routes.ARG_QUERY) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
            navArgument(Routes.ARG_CRASH_REPORT) {
                type = NavType.BoolType
                defaultValue = false
            },
        ),
    ) {
        ViewerScreen(onBack = onBack)
    }
}

private const val NAV_DURATION_MS = 300

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
    val context = LocalContext.current
    val app = context.applicationContext as MainApplication
    val leaveViewer: () -> Unit = { if (!navController.popBackStack()) activity?.finish() }
    val twoPane = rememberTwoPane()

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
            // Single-top so a double tap on the icon cannot stack two Settings screens.
            val openSettings: () -> Unit = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } }
            if (twoPane) {
                // A document opens beside the list, so nothing here navigates to the viewer route.
                HomeWithViewer(onOpenSettings = openSettings)
            } else {
                // Still registered for share intents and hardware keyboards, though no UI navigates here.
                HomeScreen(
                    onOpenDocument = { id, query -> navController.navigate(Routes.viewer(id, query)) },
                    onOpenSettings = openSettings,
                )
            }
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onOpenCrashReports = { navController.navigate(Routes.CRASH_REPORTS) { launchSingleTop = true } },
            )
        }
        composable(Routes.CRASH_REPORTS) {
            CrashReportsScreen(
                onBack = { navController.popBackStack() },
                onOpenReport = { file ->
                    val id = app.repository.openCrashReport(file)
                    navController.navigate(Routes.viewer(id, isCrashReport = true))
                },
            )
        }
        viewerDestination(onBack = leaveViewer)
    }
}
