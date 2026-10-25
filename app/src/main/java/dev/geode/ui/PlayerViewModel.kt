package dev.geode.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.export.ExportAspect
import dev.geode.export.ExportCodec
import dev.geode.export.ExportRange
import dev.geode.playback.PlaybackEngine
import dev.geode.render.SceneFactory
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The old player screens over Playback's store (#17), plus the App UI's hold on the engine and the
 * fresh start the live analysis and the export's analysis get on a new song.
 *
 * This ViewModel is created unconditionally by `AppRoot` and every Geode ViewModel shares the
 * Activity's store, so [onCleared] runs exactly once, when the whole UI is going away - the moment
 * the engine hold is released.
 */
@HiltViewModel
class PlayerViewModel
    @Inject
    constructor(
        private val application: Application,
        private val playback: OldScreenPlayback,
        private val library: OldScreenLibrary,
        private val settings: SettingsForOldScreens,
        private val export: ExportController,
        private val analysis: TrackAnalysisController,
    ) : ViewModel() {
        // The engine's UI hold, kept for the App UI's lifetime; the live Visual holds it per bind too.
        private val engineSession = PlaybackEngine.acquireForUi(application)

        private val engine = engineSession.analysis

        init {
            followPlayback()
        }

        // Playback: every call goes to Playback's app-scoped store, and what is shown is its state (#17).

        val uiState: StateFlow<PlayerUiState> get() = playback.uiState

        val queue: StateFlow<QueueUiState> get() = playback.queue

        val abLoop: StateFlow<AbLoop?> get() = playback.abLoop

        val historyTick: StateFlow<Int> get() = playback.historyTick

        val favourites: StateFlow<Set<TrackId>> get() = library.favourites

        val sleepTimerRemaining: StateFlow<Millis?> get() = playback.sleepTimerRemaining

        fun toggleShuffle() = playback.toggleShuffle()

        fun cycleRepeatMode() = playback.cycleRepeatMode()

        /** Remembers [minutes] as the timer's last length, as the old player did, and starts it. */
        fun startSleepTimer(minutes: Int) {
            val prefs = settings.prefs.value
            if (minutes > 0) settings.setPlayerPrefs(prefs.copy(sleepTimerMinutes = minutes))
            playback.startSleepTimer(minutes, prefs.sleepFinishTrack)
        }

        fun cancelSleepTimer() = playback.cancelSleepTimer()

        fun recentlyPlayed(): List<QueueTrack> = playback.recentlyPlayed()

        fun currentTrackUri(): TrackId? = playback.currentTrack()

        fun toggleFavourite(uri: TrackId? = null) {
            (uri ?: currentTrackUri())?.let(library::toggleFavourite)
        }

        fun cycleAbLoop() = playback.cycleAbLoop()

        fun clearAbLoop() = playback.clearAbLoop()

        fun removeQueueItem(index: Int) = playback.removeQueueItem(index)

        fun moveQueueItem(
            from: Int,
            to: Int,
        ) = playback.moveQueueItem(from, to)

        fun resumeLastPlayed() = playback.resumeLastPlayed()

        fun playNext(uri: TrackId) = playback.playNext(uri)

        fun enqueue(uri: TrackId) = playback.enqueue(uri)

        fun shuffleAllHistory() = playback.shuffleAllHistory()

        fun playFrom(
            tracks: List<QueueTrack>,
            startUri: TrackId,
        ) = playback.playFrom(tracks, startUri)

        fun open(uris: List<Uri>) = playback.open(uris)

        fun playQueueIndex(index: Int) = playback.playQueueIndex(index)

        fun next() = playback.next()

        fun previous() = playback.previous()

        fun togglePlayPause() = playback.togglePlayPause()

        fun seekToMs(position: Millis) = playback.seekToMs(position)

        fun seekTo(fraction: Float) = playback.seekTo(fraction)

        fun startExport(
            aspect: ExportAspect,
            fps: Int,
            sceneFactory: SceneFactory,
            destination: Uri? = null,
            loopSafe: Boolean = false,
            range: ExportRange? = null,
            codec: ExportCodec = ExportCodec.H264,
        ) = export.startExport(aspect, fps, sceneFactory, destination, loopSafe, range, codec)

        fun startLoopRender(
            aspect: ExportAspect,
            codec: ExportCodec,
            fps: Int,
            sceneFactory: SceneFactory,
            loopMs: Long,
            crossfadeMs: Long,
            drift: dev.geode.export.TimeOfDayDrift,
            audioClips: List<Uri>,
            destination: Uri? = null,
        ) = export.startLoopRender(aspect, codec, fps, sceneFactory, loopMs, crossfadeMs, drift, audioClips, destination)

        /** A new song: the live analysis starts afresh and the export's cached analysis is dropped. */
        private fun followPlayback() {
            viewModelScope.launch {
                playback.state
                    .map { OldScreenModels.queueOf(it.player)?.current?.id }
                    .distinctUntilChanged()
                    .collect { onTrackChanged() }
            }
        }

        private fun onTrackChanged() {
            engine.reset()
            analysis.timeline = null
        }

        override fun onCleared() {
            PlaybackEngine.releaseUi()
        }
    }
