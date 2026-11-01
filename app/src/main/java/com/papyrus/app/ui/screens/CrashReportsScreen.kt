package com.papyrus.app.ui.screens

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.papyrus.app.R
import com.papyrus.app.crash.CrashReportStore
import java.io.File
import java.text.DateFormat
import java.util.Date

private val ScreenPadding = 16.dp
private val CardCorner = 16.dp
private val RowMinHeight = 56.dp

data class CrashReportItem(
    val file: File,
    val timestamp: Long,
    val formattedDate: String,
    val formattedSize: String,
)

@Composable
fun CrashReportsScreen(
    onBack: () -> Unit,
    onOpenReport: (File) -> Unit,
) {
    val context = LocalContext.current
    var reportsRevision by remember { mutableIntStateOf(0) }
    val reports = remember(reportsRevision) {
        val dateFmt = DateFormat.getDateInstance(DateFormat.MEDIUM)
        val timeFmt = DateFormat.getTimeInstance(DateFormat.SHORT)
        CrashReportStore.list(context).map { file ->
            val timestamp = CrashReportStore.timestamp(file)
            val dateStr = "${dateFmt.format(Date(timestamp))} ${timeFmt.format(Date(timestamp))}"
            val sizeStr = Formatter.formatShortFileSize(context, file.length())
            CrashReportItem(
                file = file,
                timestamp = timestamp,
                formattedDate = dateStr,
                formattedSize = sizeStr,
            )
        }
    }

    var reportToDelete by remember { mutableStateOf<CrashReportItem?>(null) }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            CrashReportsHeader(onBack = onBack)

            if (reports.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(ScreenPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.crash_reports_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = ScreenPadding),
                ) {
                    item {
                        Spacer(Modifier.height(8.dp))
                    }
                    items(reports, key = { it.file.absolutePath }) { item ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(CardCorner),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                        ) {
                            CrashReportRow(
                                item = item,
                                onClick = { onOpenReport(item.file) },
                                onDelete = { reportToDelete = item },
                            )
                        }
                    }
                    item {
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    reportToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { reportToDelete = null },
            title = { Text(stringResource(R.string.crash_reports_delete_confirm_title)) },
            text = { Text(stringResource(R.string.crash_reports_delete_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        CrashReportStore.delete(target.file)
                        reportsRevision++
                        reportToDelete = null
                    },
                ) {
                    Text(stringResource(R.string.crash_reports_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { reportToDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun CrashReportsHeader(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
        Text(
            text = stringResource(R.string.crash_reports_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .semantics { heading() },
        )
    }
}

@Composable
private fun CrashReportRow(
    item: CrashReportItem,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = item.formattedDate,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${item.file.name} • ${item.formattedSize}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Default.Delete,
                contentDescription = stringResource(R.string.crash_reports_delete),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
