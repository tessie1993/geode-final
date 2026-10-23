package dev.geode.ui.shell

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.diagnostics.api.Diagnostics
import dev.geode.feature.library.api.Library
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.visuals.api.Visuals
import dev.geode.visuals.PresetSharing
import dev.geode.visuals.VisualSnapshots
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the App UI shell reads and drives: the App UI's store, and the stores of the features whose
 * own routes the shell shows (#17). It saves the App UI as it changes and, in a new process, puts it
 * back as it was.
 */
@HiltViewModel
// Feature's Command and Fact are in/out, so Kotlin gives these constructor parameters Java wildcards
// the provided stores lack; without this Dagger finds no binding for them.
@JvmSuppressWildcards
class ShellViewModel
    @Inject
    constructor(
        val appUi: AppUi,
        val playback: Playback,
        val library: Library,
        val customization: CustomizationFeature,
        val visuals: Visuals,
        val snapshots: VisualSnapshots,
        val sharing: PresetSharing,
        val settings: Settings,
        val diagnostics: Diagnostics,
        saved: SavedStateHandle,
    ) : ViewModel() {
        /**
         * Visuals off (#196): while on, nothing draws the Visual and nothing analyses for it. Settings'
         * Battery section owns the switch; until its value is in Settings' :api, the Visual stays on.
         */
        val visualsOff: StateFlow<Boolean> = MutableStateFlow(false)

        init {
            // The store outlives an Activity, so only a fresh one (a new process) is put back.
            if (appUi.state.value == AppUiState()) AppUiSaving.restore(saved[SAVED]).forEach(appUi::send)
            viewModelScope.launch { appUi.state.collect { saved[SAVED] = AppUiSaving.save(it) } }
        }

        private companion object {
            const val SAVED = "app_ui"
        }
    }
