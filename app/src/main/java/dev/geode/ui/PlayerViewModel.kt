package dev.geode.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.media3.common.util.UnstableApi
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.geode.di.PlayerSessionProvider
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.export.ExportAspect
import dev.geode.export.ExportCodec
import dev.geode.export.ExportRange
import dev.geode.render.SceneFactory
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@OptIn(UnstableApi::class)
@HiltViewModel
class PlayerViewModel
    @Inject
    constructor(
        private val sessions: PlayerSessionProvider,
        private val playback: OldScreenPlayback,
        private val library: OldScreenLibrary,
    ) : ViewModel() {
        private val session: PlayerSession = sessions.get()

        // Playback: every call goes to Playback's app-scoped store, and what is shown is its state (#17).

        val uiState: StateFlow<PlayerUiState> get() = playback.uiState

        val queue: StateFlow<QueueUiState> get() = playback.queue

        val abLoop: StateFlow<AbLoop?> get() = playback.abLoop

        val playbackNotice: StateFlow<String?> get() = session.playbackNotice

        val vizState: StateFlow<VizUiState> get() = session.vizState

        val historyTick: StateFlow<Int> get() = playback.historyTick

        val favourites: StateFlow<Set<TrackId>> get() = library.favourites

        val lyrics: StateFlow<Lyrics?> get() = session.lyrics

        val sleepTimerRemaining: StateFlow<Millis?> get() = playback.sleepTimerRemaining


        fun hasMicPermission(): Boolean = session.hasMicPermission()

        fun toggleShuffle() = playback.toggleShuffle()

        fun cycleRepeatMode() = playback.cycleRepeatMode()


        /** Remembers [minutes] as the timer's last length, as the old player did, and starts it. */
        fun startSleepTimer(minutes: Int) {
            val prefs = session.playerPrefs.value
            if (minutes > 0) session.setPlayerPrefs(prefs.copy(sleepTimerMinutes = minutes))
            playback.startSleepTimer(minutes, prefs.sleepFinishTrack)
        }

        fun cancelSleepTimer() = playback.cancelSleepTimer()

        fun recentlyPlayed(): List<QueueTrack> = playback.recentlyPlayed()

        fun currentTrackUri(): TrackId? = session.currentTrackUri()

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

        fun playFromSearch(query: String) = playback.playFromSearch(query)

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

        fun clearPlaybackNotice() = session.clearPlaybackNotice()

        fun startExport(
            aspect: ExportAspect,
            fps: Int,
            sceneFactory: SceneFactory,
            destination: Uri? = null,
            loopSafe: Boolean = false,
            range: ExportRange? = null,
            codec: ExportCodec = ExportCodec.H264,
        ) = session.startExport(aspect, fps, sceneFactory, destination, loopSafe, range, codec)

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
        ) = session.startLoopRender(aspect, codec, fps, sceneFactory, loopMs, crossfadeMs, drift, audioClips, destination)

        /**
         * This ViewModel owns the session's teardown. `AppRoot` creates it unconditionally and
         * every Geode ViewModel shares the Activity's store, so this runs exactly once, when the
         * whole UI is going away — see [dev.geode.di.PlayerSessionProvider].
         */
        override fun onCleared() {
            sessions.shutdown()
        }
    }
