package io.github.pixelrick420.papyrus.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.pixelrick420.papyrus.MainApplication
import io.github.pixelrick420.papyrus.ui.screens.HomeViewModel
import io.github.pixelrick420.papyrus.ui.screens.SettingsViewModel
import io.github.pixelrick420.papyrus.ui.screens.ViewerViewModel

object AppViewModelProvider {
    val Factory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            HomeViewModel(
                app().repository,
                app().search,
                app().thumbnailLoader,
                app().applicationScope,
                app().settings,
            )
        }
        initializer { ViewerViewModel(app(), createSavedStateHandle()) }
        initializer { SettingsViewModel(app().settings) }
    }
}

private fun CreationExtras.app(): MainApplication = this[APPLICATION_KEY] as MainApplication
