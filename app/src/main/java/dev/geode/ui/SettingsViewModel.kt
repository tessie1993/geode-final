package dev.geode.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.geode.data.ExportDefaults
import dev.geode.data.PlayerPrefs
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val settings: SettingsForOldScreens,
    ) : ViewModel() {
        val guiPrefs: StateFlow<GuiPrefs> get() = settings.guiPrefs

        val userDataLoaded: StateFlow<Boolean> get() = settings.loaded

        val playerPrefs: StateFlow<PlayerPrefs> get() = settings.prefs

        fun setGuiPrefs(prefs: GuiPrefs) {
            viewModelScope.launch { settings.setGuiPrefs(prefs) }
        }

        /** What new renders start with, from Settings (#17). */
        fun exportDefaults(): ExportDefaults = settings.exportDefaults()

        fun saveExportDefaults(defaults: ExportDefaults) = settings.saveExportDefaults(defaults)
    }
