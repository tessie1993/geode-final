package dev.geode.audio.adapter

import android.app.Application
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.geode.audio.AppForeground
import dev.geode.audio.LiveInputPolicy
import dev.geode.audio.MicCapture
import dev.geode.engine.audio.FeedSource
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.playback.NativePlayer
import dev.geode.playback.PlaybackEngine
import dev.geode.playback.PlaybackSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withContext

/**
 * The [AudioAdapter] over the pipeline the app runs today: [PlaybackEngine]'s session (its player and
 * its analysis) and [MicCapture], both writing into the Visual's one audio feed.
 *
 * Nothing is acquired until the first call or the first collector of [announcements], so building
 * this changes nothing. From then on it holds the engine's UI hold, listens to the player, and
 * chooses what the Visual hears, for the life of the process: a capture makes the microphone the
 * feed's one source, and stopping it gives the feed back to the player. The analysis follows the
 * feed's source at that source's rate and starts over on each change.
 *
 * Playback is announced from the player's own listener callbacks (the media session's player), so
 * nothing here polls. Capture is announced from the pump's callbacks. The microphone is the only
 * Live input; line-in is removed (owner decision).
 *
 * The player and the analysis are main-thread objects, so everything runs on [main]. [nativeEngine]
 * says which player to open, Geode's own or ExoPlayer, and is waited for before the engine opens.
 */
@OptIn(UnstableApi::class)
class NativeAudioAdapter(
    private val application: Application,
    private val nativeEngine: suspend () -> Boolean,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val fadeLength: () -> Millis = { Millis.ZERO },
    private val crossfadeCurve: () -> Int = { EQUAL_POWER_CURVE },
    private val onOpened: (PlaybackSession) -> Unit = {},
) : AudioAdapter {
    private val announced = MutableSharedFlow<AudioAnnouncement>(extraBufferCapacity = BUFFER)

    // Touched on main only.
    private var pipeline: Pipeline? = null

    // The crossfade and gapless choice Playback set last, kept so a new curve can be applied with them.
    private var transitions: Pair<Millis, Boolean>? = null

    private val fade = PlayPauseFade(CoroutineScope(SupervisorJob() + main))

    override val announcements: Flow<AudioAnnouncement> =
        announced.onSubscription {
            withContext(main) { opened().whereThingsStand() }.forEach { emit(it) }
        }

    override suspend fun captureSources(): Set<CaptureSource> =
        withContext(main) {
            buildSet {
                if (application.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)) add(CaptureSource.MICROPHONE)
            }
        }

    override suspend fun startCapture(source: CaptureSource): CaptureFailure? = withContext(main) { opened().beginCapture(source) }

    override suspend fun stopCapture() {
        withContext(main) { pipeline?.endCapture() }
    }

    override suspend fun load(
        track: TrackId,
        from: Millis,
    ) {
        withContext(main) {
            val player = opened().player
            fade.settle()
            player.setMediaItem(mediaItem(track), from.value)
            player.prepare()
        }
    }

    /**
     * Plays or pauses, fading over the player settings' fade length when there is one: in on play, out
     * to silence before a pause (the old player's fades, #17). One fade at a time: a newer play or pause
     * takes over from the one running, and does not wait for it.
     */
    override suspend fun setPlaying(playing: Boolean) {
        withContext(main) { fade.setPlaying(opened().faded, playing, fadeLength()) }
    }

    override suspend fun seekTo(position: Millis) {
        withContext(main) {
            val player = opened().player
            fade.settle()
            player.seekTo(position.value)
        }
    }

    override suspend fun setTransitions(
        crossfade: Millis,
        gapless: Boolean,
    ) {
        withContext(main) {
            transitions = crossfade to gapless
            applyTransitions(opened())
        }
    }

    /** The sleep timer runs out in [length]: what plays fades out to silence by then, still playing. */
    suspend fun fadeOutForSleep(length: Millis) {
        withContext(main) { pipeline?.let { fade.fadeOut(it.faded, length) } }
    }

    /** The sleep timer was cancelled while it faded the music out: back to full volume. */
    suspend fun endSleepFade() {
        withContext(main) { pipeline?.let { fade.restore(it.faded) } }
    }

    /** The crossfade curve changed in Settings: the open player crossfades with the new one from now on. */
    suspend fun crossfadeCurveChanged() {
        withContext(main) { pipeline?.let(::applyTransitions) }
    }

    // Only Geode's own player crossfades; ExoPlayer joins the tracks of its playlist without a gap.
    private fun applyTransitions(pipeline: Pipeline) {
        val (crossfade, gapless) = transitions ?: return
        (pipeline.player as? NativePlayer)?.applyPrefs(crossfade.value.toInt(), crossfadeCurve(), gapless)
    }

    override suspend fun prepareNext(track: TrackId?) {
        withContext(main) {
            val player = opened().player
            val after = player.currentMediaItemIndex + 1
            if (player.mediaItemCount > after) player.removeMediaItems(after, player.mediaItemCount)
            track?.let { player.addMediaItem(mediaItem(it)) }
        }
    }

    private fun mediaItem(track: TrackId): MediaItem =
        MediaItem
            .Builder()
            .setMediaId(track.value)
            .setUri(track.value)
            .build()

    /**
     * The pipeline, opened on first use: only once [nativeEngine] has answered, so the player is the
     * one Settings choose and never one picked before the stored settings were read (#17).
     */
    private suspend fun opened(): Pipeline {
        pipeline?.let { return it }
        val native = nativeEngine()
        // Another call may have opened it while this one waited for Settings.
        pipeline?.let { return it }
        val session = PlaybackEngine.acquireForUi(application)
        session.openPlayer(native)
        return Pipeline(session).also { pipeline = it }.also { onOpened(session) }
    }

    private fun announce(announcement: AudioAnnouncement) {
        announced.tryEmit(announcement)
    }

    private inner class Pipeline(
        private val session: PlaybackSession,
    ) {
        val player: Player get() = session.player

        /** The player as the play/pause fade drives it. */
        val faded: FadedPlayer =
            object : FadedPlayer {
                override var volume: Float
                    get() = player.volume
                    set(value) {
                        player.volume = value
                    }
                override val playWhenReady: Boolean get() = player.playWhenReady
                override val isPlaying: Boolean get() = player.isPlaying

                override fun play() = player.play()

                override fun pause() = player.pause()
            }

        private val mainHandler = Handler(Looper.getMainLooper())

        // Live input writes into the feed as its own source, heard only while a capture runs.
        private val liveSink = session.feed.sink(FeedSource.LIVE)

        private val mic =
            MicCapture(application, liveSink).apply {
                onEnded = { mainHandler.post { lose() } }
                onSilenceChanged = { quiet -> announce(CaptureAnnouncement.QuietChanged(quiet)) }
                onSilencedChanged = { now -> silencedChanged(now) }
                // A failed read reopened the capture: what follows is not continuous with what came before.
                onRestarted = { rate ->
                    liveSink.rateHz = rate
                    session.feed.flush(FeedSource.LIVE)
                }
            }

        private var capturing: CaptureSource? = null

        // The app went to the background while capturing: the microphone is let go until it comes back.
        private var suspended = false

        private val foreground = AppForeground(application) { started -> foregroundChanged(started) }

        private val playerListener =
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    announce(if (isPlaying) PlaybackAnnouncement.Started(mark()) else PlaybackAnnouncement.Paused(mark()))
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                        // The analysis starts over at the new place, so the Visual does not react to a jump.
                        session.analysis.reset()
                        announce(PlaybackAnnouncement.Seeked(mark()))
                    }
                }

                override fun onMediaItemTransition(
                    mediaItem: MediaItem?,
                    reason: Int,
                ) {
                    announce(PlaybackAnnouncement.TrackChanged(mediaItem?.mediaId?.let(::TrackId), mark()))
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) announce(PlaybackAnnouncement.Ended)
                }

                override fun onPlayerError(error: PlaybackException) {
                    announce(PlaybackAnnouncement.Failed(error.errorCodeName))
                }

                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    session.audioFx.attach(audioSessionId)
                }
            }

        init {
            player.addListener(playerListener)
            session.exoPlayer?.let { session.audioFx.attach(it.audioSessionId) }
        }

        fun whereThingsStand(): List<AudioAnnouncement> {
            val mark = mark()
            val track = player.currentMediaItem?.mediaId?.let(::TrackId)
            val playing = if (mark.moving) PlaybackAnnouncement.Started(mark) else PlaybackAnnouncement.Paused(mark)
            val lost = suspended || mic.silenced
            val capture = capturing?.let { if (lost) CaptureAnnouncement.Lost(it) else CaptureAnnouncement.Running(it) }
            return listOfNotNull(PlaybackAnnouncement.TrackChanged(track, mark), playing, capture)
        }

        fun beginCapture(source: CaptureSource): CaptureFailure? {
            if (capturing == source && (mic.active || suspended)) return null
            endCapture()
            val failure = startMic()
            if (failure != null) return failure.toCaptureFailure()
            // Live input replaces the player in the Visual; the feed starts a new epoch and the analysis over.
            session.feed.select(FeedSource.LIVE)
            capturing = source
            announce(CaptureAnnouncement.Running(source))
            if (!foreground.started) {
                // Started from the background (the Quick Settings tile): it waits for the app, as after a stop.
                foregroundChanged(false)
            } else if (mic.silenced) {
                // Silenced from the start: another app holds the microphone.
                announce(CaptureAnnouncement.Lost(source))
            }
            return null
        }

        fun endCapture() {
            if (capturing == null && !mic.active) return
            mic.stop()
            capturing = null
            suspended = false
            // The player's feed comes back to the Visual.
            session.feed.select(FeedSource.PLAYER)
        }

        private fun startMic(): MicCapture.Failure? {
            val failure = mic.start { rate -> liveSink.rateHz = rate }
            // The scenes' window, never the analysis: MilkDrop's waves from a room as big as from a track.
            if (failure == null) session.feed.setWindowGain(FeedSource.LIVE, LiveInputPolicy.windowGain(mic.unprocessed))
            return failure
        }

        /** The capture ended by itself: reads kept failing after every retry. */
        private fun lose() {
            val source = capturing ?: return
            endCapture()
            announce(CaptureAnnouncement.Lost(source))
        }

        /** Another app, the privacy toggle or the background silenced the microphone, or let it go again. */
        private fun silencedChanged(silenced: Boolean) {
            val source = capturing ?: return
            if (suspended) return
            announce(if (silenced) CaptureAnnouncement.Lost(source) else CaptureAnnouncement.Running(source))
        }

        /**
         * The app went to the background or came back. There is no microphone foreground service, so the
         * capture stops with the app and is lost meanwhile; it runs again when the app starts again.
         */
        private fun foregroundChanged(started: Boolean) {
            val source = capturing ?: return
            if (!started && !suspended) {
                mic.stop()
                suspended = true
                announce(CaptureAnnouncement.Lost(source))
            } else if (started && suspended) {
                // Still suspended while it starts, so a silencing found on the way is announced once, here.
                val failure = startMic()
                suspended = false
                if (failure == null) {
                    announce(if (mic.silenced) CaptureAnnouncement.Lost(source) else CaptureAnnouncement.Running(source))
                } else {
                    // The microphone will not open again: Live input stays lost, and the player comes back.
                    endCapture()
                }
            }
        }

        private fun mark(): PlaybackMark =
            PlaybackMark(
                position = Millis(player.currentPosition),
                at = Millis(SystemClock.elapsedRealtime()),
                moving = player.isPlaying,
            )
    }

    private fun MicCapture.Failure.toCaptureFailure(): CaptureFailure =
        when (this) {
            MicCapture.Failure.PERMISSION -> CaptureFailure.PERMISSION
            MicCapture.Failure.UNAVAILABLE -> CaptureFailure.UNAVAILABLE
        }

    private companion object {
        const val BUFFER = 64

        /** The crossfade curve Geode's player uses when nothing says otherwise: equal power. */
        const val EQUAL_POWER_CURVE = 1
    }
}
