package com.papyrus.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.papyrus.app.ui.navigation.PapyrusNavGraph
import com.papyrus.app.ui.theme.PapyrusTheme
import kotlin.coroutines.cancellation.CancellationException

class MainActivity : ComponentActivity() {

    private var externalDocument by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) externalDocument = viewUri(intent)

        val repository = (application as MainApplication).repository
        setContent {
            PapyrusTheme {
                PapyrusNavGraph(
                    externalDocument = externalDocument,
                    resolveExternalDocument = { uri ->
                        try {
                            repository.register(uri)
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
}
