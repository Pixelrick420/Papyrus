package com.papyrus.app.ui.screens

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.papyrus.app.BuildConfig
import com.papyrus.app.R
import com.papyrus.app.data.AppSettings
import com.papyrus.app.data.SettingsRepository
import com.papyrus.app.data.ThemeMode
import com.papyrus.app.ui.AppViewModelProvider
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings

    fun setThemeMode(mode: ThemeMode) = repository.setThemeMode(mode)

    fun setAutosaveExternal(enabled: Boolean) = repository.setAutosaveExternal(enabled)

    fun setAutosaveEncrypted(enabled: Boolean) = repository.setAutosaveEncrypted(enabled)
}

/** Same inset as Home and the viewer, so cards line up with the Home list. */
private val ScreenPadding = 16.dp

private val CardCorner = 16.dp

private val RowMinHeight = 56.dp

private const val SOURCE_URL = "https://github.com/Pixelrick420/Papyrus"

@StringRes
private fun ThemeMode.label(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = AppViewModelProvider.Factory),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var themeDialogOpen by remember { mutableStateOf(false) }

    // Opened in the person's browser; Papyrus itself still never touches the network.
    val openSource: () -> Unit = {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, SOURCE_URL.toUri()))
        } catch (ignored: ActivityNotFoundException) {
            // No browser installed: nothing to open the link with.
        }
    }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                // Consuming the Scaffold padding first stops the navigation-bar inset being counted twice.
                .consumeWindowInsets(padding),
        ) {
            SettingsHeader(onBack = onBack)

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = ScreenPadding, end = ScreenPadding, bottom = 24.dp),
            ) {
                SectionLabel(stringResource(R.string.settings_section_appearance), first = true)
                SettingsCard {
                    SettingsRow(
                        title = stringResource(R.string.settings_theme),
                        value = stringResource(settings.themeMode.label()),
                        modifier = Modifier.clickable(role = Role.Button) { themeDialogOpen = true },
                    )
                }

                SectionLabel(stringResource(R.string.settings_section_library))
                SettingsCard {
                    SwitchRow(
                        title = stringResource(R.string.settings_autosave_external),
                        description = stringResource(R.string.settings_autosave_external_desc),
                        checked = settings.autosaveExternal,
                        onCheckedChange = viewModel::setAutosaveExternal,
                    )
                    CardDivider()
                    SwitchRow(
                        title = stringResource(R.string.settings_autosave_encrypted),
                        description = stringResource(R.string.settings_autosave_encrypted_desc),
                        checked = settings.autosaveEncrypted,
                        onCheckedChange = viewModel::setAutosaveEncrypted,
                    )
                }

                SectionLabel(stringResource(R.string.settings_section_about))
                SettingsCard {
                    SettingsRow(
                        title = stringResource(R.string.app_name),
                        value = stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME),
                    )
                    CardDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_about_source),
                        value = stringResource(R.string.settings_about_source_value),
                        modifier = Modifier.clickable(role = Role.Button, onClick = openSource),
                    )
                    CardDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_about_license),
                        value = stringResource(R.string.settings_about_license_value),
                    )
                }
            }
        }
    }

    if (themeDialogOpen) {
        ThemeDialog(
            selected = settings.themeMode,
            onSelect = { mode ->
                viewModel.setThemeMode(mode)
                themeDialogOpen = false
            },
            onDismiss = { themeDialogOpen = false },
        )
    }
}

/** Flat title row, the same shape as the viewer's header. */
@Composable
private fun SettingsHeader(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .semantics { heading() },
        )
    }
}

@Composable
private fun SectionLabel(text: String, first: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = 8.dp, top = if (first) 8.dp else 24.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

/** Rounded surface matching Home's document rows; clips its rows' ripples to the corners. */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCorner),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(content = content)
    }
}

@Composable
private fun CardDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** A label over its value; clickable when the caller passes a click modifier. */
@Composable
private fun SettingsRow(title: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/** The whole row toggles, not just the switch, and the switch itself stays out of the click handling. */
@Composable
private fun SwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RowMinHeight)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ThemeDialog(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_theme)) },
        text = {
            Column(Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = mode == selected,
                                onClick = { onSelect(mode) },
                                role = Role.RadioButton,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == selected, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Text(stringResource(mode.label()), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
