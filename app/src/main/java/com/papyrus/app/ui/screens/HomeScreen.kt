package com.papyrus.app.ui.screens

import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papyrus.app.R
import com.papyrus.app.data.DocumentEntity
import com.papyrus.app.data.DocumentFormat
import com.papyrus.app.data.DocumentRepository
import com.papyrus.app.data.OpenPersistableDocument
import com.papyrus.app.data.ThumbnailLoader
import com.papyrus.app.ui.AppViewModelProvider
import com.papyrus.app.ui.UiText
import com.papyrus.app.ui.asString
import com.papyrus.app.ui.components.DocumentThumbnail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(
    private val repository: DocumentRepository,
    val thumbnailLoader: ThumbnailLoader,
) : ViewModel() {

    val documents = repository.documents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = Channel<UiText>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun onDocumentPicked(uri: Uri, open: (Long) -> Unit) {
        viewModelScope.launch {
            try {
                open(repository.register(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.send(UiText(R.string.home_open_failed))
            }
        }
    }

    fun remove(document: DocumentEntity) {
        viewModelScope.launch {
            repository.remove(document)
            _messages.send(UiText(R.string.home_removed))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenDocument: (Long) -> Unit,
    viewModel: HomeViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    val openFile = rememberLauncherForActivityResult(OpenPersistableDocument()) { uri ->
        if (uri != null) viewModel.onDocumentPicked(uri, onOpenDocument)
    }

    // Per-document read grant is enough: the app never writes to a document it opened.
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbar.showSnackbar(it.asString(context)) }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { openFile.launch(DocumentFormat.pickerMimeTypes) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.action_open_file))
                }
            }
            if (documents.isEmpty()) {
                EmptyState(Modifier.weight(1f).fillMaxWidth())
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(documents, key = { it.id }) { doc ->
                        DocumentRow(
                            doc = doc,
                            loader = viewModel.thumbnailLoader,
                            onClick = { onOpenDocument(doc.id) },
                            onRemove = { viewModel.remove(doc) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(modifier.padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleMedium)
        Image(
            painter = painterResource(R.drawable.open_box),
            contentDescription = null,
            modifier = Modifier.padding(top = 16.dp).size(160.dp),
        )
    }
}

@Composable
private fun DocumentRow(
    doc: DocumentEntity,
    loader: ThumbnailLoader,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val size = Formatter.formatShortFileSize(context, doc.sizeBytes)
    val opened = DateUtils.getRelativeTimeSpanString(doc.lastOpenedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { DocumentThumbnail(doc, loader) },
        headlineContent = { Text(doc.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text("$size · $opened") },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_remove_from_list))
            }
        },
    )
}
