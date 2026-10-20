package dev.geode.audio.adapter

import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.geode.audio.MicCapture
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.playback.PlaybackEngine
import dev.geode.playback.PlaybackSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The [AudioAdapter] over the pipeline the app runs today: [PlaybackEngine]'s session (its player,
 * its capture sink and its analysis) and [MicCapture] feeding that sink.
 *
 * Nothing is acquired until the first call that needs the player or a capture, so building this
 * changes nothing. From then on it holds the engine's UI hold for the life of the process. It does
 * what the old capture controller did to the pipeline, and no more: a capture resets the analysis
 * and runs it at the capture's rate; stopping resets it again and goes back to the player's rate.
 *
 * Everything runs on [main], as it does today: the player and the analysis are main-thread objects.
 */
class NativeAudioAdapter(
    private val application: Application,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
) : AudioAdapter {
    // Touched on main only.
    private var pipeline: Pipeline? = null

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

    override suspend fun captureStatus(): CaptureStatus = withContext(main) { pipeline?.captureStatus() ?: CaptureStatus.Idle }

    override suspend fun load(
        track: TrackId,
        from: Millis,
    ) {
        withContext(main) {
            val player = opened().player
            player.setMediaItem(
                MediaItem
                    .Builder()
                    .setMediaId(track.value)
                    .setUri(track.value)
                    .build(),
                from.value,
            )
            player.prepare()
        }
    }

    override suspend fun setPlaying(playing: Boolean) =
        withContext(main) {
            val player = opened().player
            if (playing) player.play() else player.pause()
        }

    override suspend fun seekTo(position: Millis) =
        withContext(main) {
            opened().player.seekTo(position.value)
        }

    override suspend fun playbackStatus(): PlaybackStatus =
        withContext(main) {
            val player = opened().player
            PlaybackStatus(
                track = player.currentMediaItem?.mediaId?.let(::TrackId),
                position = Millis(player.currentPosition),
                playing = player.playWhenReady,
                ended = player.playbackState == Player.STATE_ENDED,
            )
        }

    private fun opened(): Pipeline = pipeline ?: Pipeline(PlaybackEngine.acquireForUi(application)).also { pipeline = it }

    private fun lineInput(): AudioDeviceInfo? =
        application
            .getSystemService(AudioManager::class.java)
            ?.getDevices(AudioManager.GET_DEVICES_INPUTS)
            ?.firstOrNull { it.type in LINE_IN_TYPES }

    private inner class Pipeline(
        private val session: PlaybackSession,
    ) {
        val player: Player get() = session.player

        private val mic = MicCapture(application, session.captureSink)

        private var capturing: CaptureSource? = null

        @Volatile
        private var playerRateHz = 0

        init {
            // The analysis follows the player's format, except while a capture owns it.
            session.onAudioFormat =
                { rate, _, _ ->
                    playerRateHz = rate
                    if (!mic.active) session.analysis.sampleRateHz = rate
                }
        }

        fun beginCapture(
            source: CaptureSource,
            device: AudioDeviceInfo?,
        ): CaptureFailure? {
            if (capturing == source && mic.active) return null
            endCapture()
            val failure = mic.start(device) { rate -> session.analysis.sampleRateHz = rate }
            if (failure != null) return failure.toCaptureFailure()
            session.sampleRing.beginEpoch()
            session.analysis.reset()
            capturing = source
            return null
        }

        fun captureStatus(): CaptureStatus {
            val source = capturing ?: return CaptureStatus.Idle
            if (!mic.active) {
                // The pump stopped on its own: the read failed or the device went away.
                endCapture()
                return CaptureStatus.Idle
            }
            return CaptureStatus.Running(source, quiet = mic.silenceLikely)
        }

        fun endCapture() {
            if (capturing == null && !mic.active) return
            mic.stop()
            capturing = null
            session.sampleRing.beginEpoch()
            session.analysis.reset()
            session.analysis.sampleRateHz = playerRateHz.takeIf { it > 0 } ?: FALLBACK_RATE_HZ
        }
    }

    private fun MicCapture.Failure.toCaptureFailure(): CaptureFailure =
        when (this) {
            MicCapture.Failure.PERMISSION -> CaptureFailure.PERMISSION
            MicCapture.Failure.UNAVAILABLE -> CaptureFailure.UNAVAILABLE
        }

    private companion object {
        const val FALLBACK_RATE_HZ = 44_100

        val LINE_IN_TYPES =
            setOf(
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_LINE_DIGITAL,
            )
    }
}
