package io.github.pixelrick420.papyrus

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pixelrick420.papyrus.ui.navigation.PapyrusNavGraph
import io.github.pixelrick420.papyrus.ui.theme.PapyrusTheme
import kotlin.coroutines.cancellation.CancellationException

// The sequence makes two handovers of the same URI distinct, so neither is dropped as an unchanged value.
data class Handover(val uri: Uri, val seq: Long)

class MainActivity : ComponentActivity() {

    private var externalDocument by mutableStateOf<Handover?>(null)
    private var handoverSeq = 0L
    private var lastHandledHandover: Handover? = null

    @Suppress("TooGenericExceptionCaught")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as MainApplication
        val settings = app.settings
        // Styled before the first frame so a forced theme never flashes the wrong bars.
        applySystemBars(settings.current.themeMode.isDark(deviceInDarkMode()))
        if (savedInstanceState == null) {
            externalDocument = viewUri(intent)?.let(::handover)
        } else {
            savedInstanceState.getString(KEY_HANDOVER_URI)?.let { uri ->
                val seq = savedInstanceState.getLong(KEY_HANDOVER_SEQ)
                handoverSeq = seq
                externalDocument = Handover(uri.toUri(), seq)
            }
        }

        val repository = app.repository
        setContent {
            val appSettings by settings.settings.collectAsStateWithLifecycle()
            val darkTheme = appSettings.themeMode.isDark(isSystemInDarkTheme())
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
                            Log.w(TAG, "handed-over document failed: ${e.javaClass.simpleName}")
                            null
                        }
                    },
                    onExternalDocumentHandled = {
                        lastHandledHandover = externalDocument
                        externalDocument = null
                    },
                )
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val handover = externalDocument ?: lastHandledHandover
        handover?.let {
            outState.putString(KEY_HANDOVER_URI, it.uri.toString())
            outState.putLong(KEY_HANDOVER_SEQ, it.seq)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewUri(intent)?.let { externalDocument = handover(it) }
    }

    private fun handover(uri: Uri): Handover = Handover(uri, ++handoverSeq).also { lastHandledHandover = null }

    private fun viewUri(intent: Intent?): Uri? {
        if (intent == null) return null
        val stream = if (intent.action == Intent.ACTION_SEND) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            null
        }
        return intentToDocumentUri(
            intent.action,
            intent.data?.toString(),
            stream?.toString(),
        )?.toUri()
    }

    private fun deviceInDarkMode(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    // Like enableEdgeToEdge(), but dark follows the app theme instead of the system.
    private fun applySystemBars(dark: Boolean) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark },
            navigationBarStyle = SystemBarStyle.auto(NAV_SCRIM_LIGHT, NAV_SCRIM_DARK) { dark },
        )
    }

    private companion object {
        const val TAG = "MainActivity"
        const val KEY_HANDOVER_URI = "handover_uri"
        const val KEY_HANDOVER_SEQ = "handover_seq"
        val NAV_SCRIM_LIGHT = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val NAV_SCRIM_DARK = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
