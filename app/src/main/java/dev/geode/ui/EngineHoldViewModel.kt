package dev.geode.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.currentQueue
import dev.geode.playback.EngineFailureNotices
import dev.geode.playback.PlaybackEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The App UI's hold on the engine, kept for the App UI's lifetime (the live Visual holds it per bind
 * too), the banner for failures caught at the native bridge (ADR-0004), and the fresh start the live
 * analysis gets on a new song.
 *
 * This ViewModel is created unconditionally by `AppRoot` and every Geode ViewModel shares the
 * Activity's store, so [onCleared] runs exactly once, when the whole UI is going away — the moment
 * the engine hold and the failure subscription are released.
 */
@HiltViewModel
// Playback's Command and Fact are in/out, so Kotlin gives the store parameter Java wildcards the
// provided store lacks; without this Dagger finds no binding for it.
@JvmSuppressWildcards
class EngineHoldViewModel
    @Inject
    constructor(
        private val application: Application,
        private val playback: Playback,
    ) : ViewModel() {
        private val engineSession = PlaybackEngine.acquireForUi(application)

        private val engine = engineSession.analysis

        private val _playbackNotice = MutableStateFlow<String?>(null)

        /** The engine failure the banner shows, or null when there is none. */
        val playbackNotice: StateFlow<String?> = _playbackNotice

        private val engineFailureNotices =
            EngineFailureNotices(show = { message -> viewModelScope.launch { _playbackNotice.value = application.getString(message) } })

        init {
            // A new song: the live analysis starts afresh.
            viewModelScope.launch {
                playback.state
                    .map {
                        it.player.currentQueue
                            ?.current
                            ?.id
                    }.distinctUntilChanged()
                    .collect { engine.reset() }
            }
        }

        fun clearPlaybackNotice() {
            _playbackNotice.value = null
        }

        override fun onCleared() {
            engineFailureNotices.close()
            PlaybackEngine.releaseUi()
        }
    }
