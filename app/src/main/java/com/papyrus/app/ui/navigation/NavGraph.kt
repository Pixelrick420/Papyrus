package com.papyrus.app.ui.navigation

import android.net.Uri
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

    NavHost(navController = navController, startDestination = Routes.HOME) {
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
