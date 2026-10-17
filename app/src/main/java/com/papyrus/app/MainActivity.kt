package com.papyrus.app

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.papyrus.app.ui.navigation.PapyrusNavGraph
import com.papyrus.app.ui.theme.PapyrusTheme
import kotlin.coroutines.cancellation.CancellationException

class MainActivity : ComponentActivity() {

    private var externalDocument by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MainApplication
        val settings = app.settings
        // Styled before the first frame so a forced theme never flashes the wrong bars.
        applySystemBars(settings.current.themeMode.isDark(deviceInDarkMode()))
        if (savedInstanceState == null) externalDocument = viewUri(intent)

        val repository = app.repository
        setContent {
            val appSettings by settings.settings.collectAsStateWithLifecycle()
            val darkTheme = appSettings.themeMode.isDark(isSystemInDarkTheme())
            // Re-styled on theme change so the icons contrast with the new background.
            DisposableEffect(darkTheme) {
                applySystemBars(darkTheme)
                onDispose {}
            }
            PapyrusTheme(
                darkTheme = darkTheme,
                colorblindMode = appSettings.colorblindMode,
            ) {
                PapyrusNavGraph(
                    externalDocument = externalDocument,
                    resolveExternalDocument = { uri ->
                        try {
                            repository.openHandedOver(uri)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    },
                    onExternalDocumentHandled = { externalDocument = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewUri(intent)?.let { externalDocument = it }
    }

    private fun viewUri(intent: Intent?): Uri? =
        intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data

    private fun deviceInDarkMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** Like `enableEdgeToEdge()`, but dark follows the app theme; the nav-bar scrims are the library defaults. */
    private fun applySystemBars(dark: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(NAV_SCRIM_LIGHT, NAV_SCRIM_DARK) { dark },
        )
    }

    private companion object {
        val NAV_SCRIM_LIGHT = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val NAV_SCRIM_DARK = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
