package com.papyrus.app.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.papyrus.app.MainApplication
import com.papyrus.app.ui.screens.HomeViewModel
import com.papyrus.app.ui.screens.ViewerViewModel

object AppViewModelProvider {
    val Factory: ViewModelProvider.Factory = viewModelFactory {
        initializer { HomeViewModel(app().repository, app().thumbnailLoader) }
        initializer { ViewerViewModel(app(), createSavedStateHandle()) }
    }
}

private fun CreationExtras.app(): MainApplication = this[APPLICATION_KEY] as MainApplication
