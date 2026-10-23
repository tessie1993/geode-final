package dev.geode.ui

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.geode.data.ExportDefaults
import dev.geode.data.PlayerPrefs
import dev.geode.di.PlayerSessionProvider
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val sessions: PlayerSessionProvider,
        private val settings: SettingsForOldScreens,
    ) : ViewModel() {
        private val session: PlayerSession = sessions.get()
        private val userData: UserDataRepository = session.userDataRepository

        val guiPrefs: StateFlow<GuiPrefs> get() = userData.guiPrefs

        val userDataLoaded: StateFlow<Boolean> get() = userData.loaded

        val playerPrefs: StateFlow<PlayerPrefs> get() = session.playerPrefs

        fun setGuiPrefs(prefs: GuiPrefs) = session.setGuiPrefs(prefs)

        /** What new renders start with, from Settings (#17). */
        fun exportDefaults(): ExportDefaults = settings.exportDefaults()

        fun saveExportDefaults(defaults: ExportDefaults) = settings.saveExportDefaults(defaults)
    }
