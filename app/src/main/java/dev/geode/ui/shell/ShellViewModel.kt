package dev.geode.ui.shell

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.geode.engine.bridge.NativeSnapshots
import dev.geode.feature.capture.api.Capture
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.customization.handlers.StyleCues
import dev.geode.feature.diagnostics.api.Diagnostics
import dev.geode.feature.library.api.Library
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.studio.api.Studio
import dev.geode.feature.visuals.api.Visuals
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
        val settings: Settings,
        val diagnostics: Diagnostics,
        val customization: CustomizationFeature,
        val visuals: Visuals,
        val studio: Studio,
        val capture: Capture,
        /** The Customization snapshots the live Visual reads (ADR-0004). */
        val snapshots: NativeSnapshots,
        /** The live Visual's word that Style switching is due (#282). */
        val styleCues: StyleCues,
        saved: SavedStateHandle,
    ) : ViewModel() {
        init {
            // The store outlives an Activity, so only a fresh one (a new process) is put back.
            if (appUi.state.value == AppUiState()) AppUiSaving.restore(saved[SAVED]).forEach(appUi::send)
            viewModelScope.launch { appUi.state.collect { saved[SAVED] = AppUiSaving.save(it) } }
        }

        private companion object {
            const val SAVED = "app_ui"
        }
    }
