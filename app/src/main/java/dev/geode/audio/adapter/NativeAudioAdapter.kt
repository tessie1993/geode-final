package dev.geode.audio.adapter

import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import dev.geode.audio.MicCapture
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.playback.NativePlayer
import dev.geode.playback.PlaybackEngine
import dev.geode.playback.PlaybackSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * The player and the analysis are main-thread objects, so everything runs on [main].
 */
class NativeAudioAdapter(
    private val application: Application,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val fadeLength: () -> Millis = { Millis.ZERO },
) : AudioAdapter {
    private val announced = MutableSharedFlow<AudioAnnouncement>(extraBufferCapacity = BUFFER)

    // Touched on main only.
    private var pipeline: Pipeline? = null

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
            player.setMediaItem(mediaItem(track), from.value)
            player.prepare()
        }
    }

    /**
     * Plays or pauses, fading over the player settings' fade length when there is one: in from silence
     * on play, out to silence before a pause (the old player's fades, #17).
     */
    override suspend fun setPlaying(playing: Boolean) {
        withContext(main) {
            val player = opened().player
            val fade = fadeLength()
            if (fade <= Millis.ZERO) {
                player.volume = 1f
                if (playing) player.play() else player.pause()
                return@withContext
            }
            if (playing) {
                player.volume = 0f
                player.play()
                ramp(player, from = 0f, to = 1f, fade)
            } else if (player.isPlaying) {
                ramp(player, from = player.volume, to = 0f, fade)
                player.pause()
                player.volume = 1f
            } else {
                player.pause()
            }
        }
    }

    private suspend fun ramp(
        player: Player,
        from: Float,
        to: Float,
        length: Millis,
    ) {
        val steps = (length.value / FADE_STEP_MS).coerceAtLeast(1)
        for (step in 1..steps) {
            delay(FADE_STEP_MS)
            player.volume = (from + (to - from) * step / steps).coerceIn(0f, 1f)
        }
    }

    override suspend fun seekTo(position: Millis) {
        withContext(main) { opened().player.seekTo(position.value) }
    }

    override suspend fun setTransitions(
        crossfade: Millis,
        gapless: Boolean,
    ) {
        // Only Geode's own player crossfades; ExoPlayer joins the tracks of its playlist without a gap.
        withContext(main) { (opened().player as? NativePlayer)?.applyPrefs(crossfade.value.toInt(), EQUAL_POWER_CURVE, gapless) }
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

    private fun opened(): Pipeline = pipeline ?: Pipeline(PlaybackEngine.acquireForUi(application)).also { pipeline = it }

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
        const val FADE_STEP_MS = 25L

        /** The crossfade curve the old player settings start from: equal power. */
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
