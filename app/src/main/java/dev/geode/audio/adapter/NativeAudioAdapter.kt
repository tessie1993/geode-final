package dev.geode.audio.adapter

import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import dev.geode.audio.MicCapture
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
 * The [AudioAdapter] over the pipeline the app runs today: [PlaybackEngine]'s session (its player,
 * its capture sink and its analysis) and [MicCapture] feeding that sink.
 *
 * Nothing is acquired until the first call or the first collector of [announcements], so building
 * this changes nothing. From then on it holds the engine's UI hold, listens to the player, and owns
 * the session's audio-format hook, for the life of the process. It does what the old capture
 * controller did to the pipeline, and no more: a capture resets the analysis and runs it at the
 * capture's rate; stopping resets it again and goes back to the player's rate.
 *
 * Playback is announced from the player's own listener callbacks (the media session's player), so
 * nothing here polls. Capture is announced from the pump's callbacks and from the system's
 * device-removed callback, which is how an unplugged line input is noticed.
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
                if (lineInput() != null) add(CaptureSource.LINE_IN)
            }
        }

    override suspend fun startCapture(source: CaptureSource): CaptureFailure? =
        withContext(main) {
            val device =
                when (source) {
                    CaptureSource.MICROPHONE -> null
                    CaptureSource.LINE_IN -> lineInput() ?: return@withContext CaptureFailure.UNAVAILABLE
                }
            opened().beginCapture(source, device)
        }

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

    private fun lineInput(): AudioDeviceInfo? =
        application
            .getSystemService(AudioManager::class.java)
            ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.type in LINE_IN_TYPES }

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

        private val mic =
            MicCapture(application, session.captureSink).apply {
                onEnded = { mainHandler.post { lose() } }
                onSilenceChanged = { quiet -> announce(CaptureAnnouncement.QuietChanged(quiet)) }
            }

        private var capturing: CaptureSource? = null
        private var device: AudioDeviceInfo? = null

        @Volatile
        private var playerRateHz = 0

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

        private val devices =
            object : AudioDeviceCallback() {
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    val unplugged = device ?: return
                    if (removedDevices?.any { it.id == unplugged.id } == true) lose()
                }
            }

        init {
            // The analysis follows the player's format, except while a capture owns it.
            session.onAudioFormat =
                { rate, _, _ ->
                    playerRateHz = rate
                    if (!mic.active) session.analysis.sampleRateHz = rate
                }
            player.addListener(playerListener)
            session.exoPlayer?.let { session.audioFx.attach(it.audioSessionId) }
            application.getSystemService(AudioManager::class.java)?.registerAudioDeviceCallback(devices, mainHandler)
        }

        fun whereThingsStand(): List<AudioAnnouncement> {
            val mark = mark()
            val track = player.currentMediaItem?.mediaId?.let(::TrackId)
            val playing = if (mark.moving) PlaybackAnnouncement.Started(mark) else PlaybackAnnouncement.Paused(mark)
            val capture = capturing?.let { CaptureAnnouncement.Running(it) }
            return listOfNotNull(PlaybackAnnouncement.TrackChanged(track, mark), playing, capture)
        }

        fun beginCapture(
            source: CaptureSource,
            input: AudioDeviceInfo?,
        ): CaptureFailure? {
            if (capturing == source && mic.active) return null
            endCapture()
            val failure = mic.start(input) { rate -> session.analysis.sampleRateHz = rate }
            if (failure != null) return failure.toCaptureFailure()
            session.sampleRing.beginEpoch()
            session.analysis.reset()
            capturing = source
            device = input
            announce(CaptureAnnouncement.Running(source))
            return null
        }

        fun endCapture() {
            if (capturing == null && !mic.active) return
            mic.stop()
            capturing = null
            device = null
            session.sampleRing.beginEpoch()
            session.analysis.reset()
            session.analysis.sampleRateHz = playerRateHz.takeIf { it > 0 } ?: FALLBACK_RATE_HZ
        }

        /** The capture ended by itself: a read failed or its device was unplugged. */
        private fun lose() {
            val source = capturing ?: return
            endCapture()
            announce(CaptureAnnouncement.Lost(source))
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
        const val FALLBACK_RATE_HZ = 44_100

        /** The crossfade curve Geode's player uses when nothing says otherwise: equal power. */
        const val EQUAL_POWER_CURVE = 1

        val LINE_IN_TYPES =
            setOf(
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_LINE_DIGITAL,
            )
    }
}
