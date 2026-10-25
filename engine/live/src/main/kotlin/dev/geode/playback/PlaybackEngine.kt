package dev.geode.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.geode.audio.AudioFxController
import dev.geode.audio.PcmRingBuffer
import dev.geode.audio.TapRenderersFactory
import dev.geode.audio.dsp.NativeDspProcessor
import dev.geode.engine.audio.AudioPresentationClock
import dev.geode.engine.audio.PcmSink
import dev.geode.engine.audio.SampleRing
import dev.geode.engine.audioandroid.PcmTap
import dev.geode.engine.audioandroid.SinkClockDriver
import dev.geode.engine.audioandroid.TapBoundaryListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class PlaybackSession internal constructor(
    private val context: Context,
) {
    val ring = PcmRingBuffer()

    val sampleRing = SampleRing(capacityFrames = 1 shl 16, channelCount = 2)

    @Volatile
    var onAudioFormat: ((sampleRateHz: Int, channelCount: Int, encoding: Int) -> Unit)? = null

    internal val presentationClock = AudioPresentationClock()

    internal val clockDriver = SinkClockDriver(presentationClock)

    val captureSink =
        PcmSink { samples, frames, channels ->
            ring.writeInterleaved(samples, frames, channels)
            sampleRing.write(samples, frames, channels)
        }

    internal val tap =
        PcmTap(captureSink) { format ->
            val hook = onAudioFormat
            if (hook != null) {
                hook(format.sampleRate.value, format.channelCount, format.encoding)
            } else {
                analysis.sampleRateHz = format.sampleRate.value
            }
        }.apply {
            boundaryListener =
                TapBoundaryListener { ended, endedFrames, begun ->
                    sampleRing.beginEpoch()
                    clockDriver.onTapBoundary(ended, endedFrames, begun)
                }
        }

    internal val dsp = NativeDspProcessor()

    private var opened: Player? = null

    /**
     * Opens the player, Geode's own when [nativeEngine] or ExoPlayer otherwise: a Settings choice (#17)
     * that whoever drives the player passes once the stored settings are read. The first call decides,
     * for the life of the session; a later one gets the same player whatever it asks for. Main thread.
     */
    fun openPlayer(nativeEngine: Boolean): Player =
        opened ?: buildPlayer(nativeEngine).also { player ->
            opened = player
            player.addListener(replayGain)
        }

    /** The player, once [openPlayer] has opened it. */
    val player: Player get() = checkNotNull(opened) { "the player is opened by openPlayer, once Settings say which" }

    val exoPlayer: ExoPlayer? get() = opened as? ExoPlayer

    // WAKE_MODE_LOCAL takes a partial wake lock while playback is active, so the CPU
    // cannot doze mid-track with the screen off. Not the WIFI variant: nothing streams.
    private fun buildPlayer(nativeEngine: Boolean): Player =
        if (nativeEngine) {
            NativePlayer(context, NativeTapPump(tap, clockDriver), NativePlayerDsp(dsp))
        } else {
            ExoPlayer
                .Builder(context, TapRenderersFactory(context, tap, clockDriver, dsp = listOf(dsp)))
                .setMediaSourceFactory(
                    androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
                        context,
                        androidx.media3.extractor.ExtractorsFactory {
                            androidx.media3.extractor
                                .DefaultExtractorsFactory()
                                .createExtractors() +
                                dev.geode.audio.AiffExtractor()
                        },
                    ),
                ).setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                ).setWakeMode(C.WAKE_MODE_LOCAL)
                .build()
        }

    val audioFx = AudioFxController(dsp)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val replayGain = ReplayGain(context.contentResolver, scope) { audioFx.setGainDb(it) }

    val analysis = dev.geode.analysis.AnalysisEngine(sampleRing)

    private val interestHook: () -> Unit = { syncAnalysis() }

    init {
        dev.geode.audio.AudioBus.onInterestChanged = interestHook
        syncAnalysis()
        scope.launch {
            analysis.features.collect {
                dev.geode.audio.AudioBus
                    .publish(it)
            }
        }
    }

    private fun syncAnalysis() {
        if (dev.geode.audio.AudioBus.hasConsumers) analysis.start(scope) else analysis.stop()
    }

    internal fun release() {
        analysis.close()
        if (dev.geode.audio.AudioBus.onInterestChanged === interestHook) {
            dev.geode.audio.AudioBus.onInterestChanged = null
        }
        scope.cancel()
        onAudioFormat = null
        audioFx.release()
        opened?.let {
            it.removeListener(replayGain)
            it.release()
        }
        opened = null
        dsp.release()
    }
}

object PlaybackEngine {
    private var app: Context? = null
    private var session: PlaybackSession? = null
    private var uiHolds = 0
    private var serviceHolds = 0

    private fun rebindTo(context: Context): Context {
        val current = context.applicationContext
        if (app !== current) {
            // Dropping the reference is not enough: the old session owns an ExoPlayer, the audio
            // effect chain, the PCM tap and a coroutine scope, none of which the GC reclaims.
            session?.release()
            session = null
            uiHolds = 0
            serviceHolds = 0
            app = current
        }
        return current
    }

    @Synchronized
    private fun sessionFor(context: Context): PlaybackSession {
        val current = rebindTo(context)
        return session ?: PlaybackSession(current).also { session = it }
    }

    @Synchronized
    fun acquireForUi(context: Context): PlaybackSession = sessionFor(context).also { uiHolds++ }

    @Synchronized
    fun releaseUi() {
        if (uiHolds > 0) uiHolds--
        releaseIfUnused()
    }

    @Synchronized
    fun acquireForService(context: Context): PlaybackSession = sessionFor(context).also { serviceHolds++ }

    @Synchronized
    fun releaseService() {
        if (serviceHolds > 0) serviceHolds--
        releaseIfUnused()
    }

    private fun releaseIfUnused() {
        if (uiHolds > 0 || serviceHolds > 0) return
        session?.release()
        session = null
        app = null
    }
}
