package dev.geode.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioRouting
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.geode.util.bestEffort
import kotlin.math.abs

/**
 * Live input: the phone's built-in microphone, the only one (CONTEXT.md). It survives real life (#297):
 *
 * - A wired or USB headset that is plugged in does not take over: the built-in mic is chosen
 *   explicitly, and chosen again whenever the system routes the capture elsewhere.
 * - Being silenced by another app, the privacy toggle or the background is reported through
 *   [onSilencedChanged] as it happens, rather than read as a quiet room after a few seconds.
 * - A failed read is retried a bounded number of times (the pump, LiveInputPolicy).
 * - 48 kHz is asked for first, and reads are small, so nearly every Visual frame has new audio.
 */
class MicCapture(
    private val context: Context,
    sink: dev.geode.engine.audio.PcmSink,
    nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : AudioCapturePump(sink, LiveInputPolicy.rates.first(), nowMs) {
    enum class Failure {
        PERMISSION,

        UNAVAILABLE,
    }

    @Volatile
    var peakLevel: Float = 0f
        private set

    @Volatile
    var silenceLikely: Boolean = false
        private set

    /** Whether the capture reads the UNPROCESSED source, which is much quieter than the voice path. */
    @Volatile
    var unprocessed: Boolean = false
        private set

    /** Whether the system silences this capture now: another app, the privacy toggle or the background. */
    @Volatile
    var silenced: Boolean = false
        private set

    override val threadName = "geode-mic"

    private val audioManager: AudioManager? get() = context.getSystemService(AudioManager::class.java)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val recordingCallback =
        object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val rec = currentRecord ?: return
                val mine = configs.firstOrNull { it.clientAudioSessionId == rec.audioSessionId } ?: return
                noteSilenced(mine.isClientSilenced)
            }
        }

    private val routingListener = AudioRouting.OnRoutingChangedListener { router -> keepBuiltIn(router as? AudioRecord) }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts capturing from the built-in microphone, the only Live input (line-in is removed). */
    @Synchronized
    fun start(onSampleRate: (Int) -> Unit = {}): Failure? {
        if (active) return null
        if (!hasPermission()) return Failure.PERMISSION
        val rec = openRecord() ?: return Failure.UNAVAILABLE
        silenced = false
        if (!startPump(rec, 1, onSampleRate)) return Failure.UNAVAILABLE
        watch(rec)
        bestEffort(TAG, "registerAudioRecordingCallback") { audioManager?.registerAudioRecordingCallback(recordingCallback, mainHandler) }
        // Already silenced when it starts (the app is in the background, the toggle is off).
        bestEffort(TAG, "activeRecordingConfiguration") { rec.activeRecordingConfiguration?.let { noteSilenced(it.isClientSilenced) } }
        return null
    }

    /** Called on the main thread each time [silenced] changes while capturing. */
    @Volatile
    var onSilencedChanged: ((Boolean) -> Unit)? = null

    private fun noteSilenced(now: Boolean) {
        if (silenced == now) return
        silenced = now
        onSilencedChanged?.invoke(now)
    }

    override fun reopen(): AudioRecord? = openRecord()

    override fun onRecordReplaced(rec: AudioRecord) = watch(rec)

    override fun onStopped() {
        bestEffort(TAG, "unregisterAudioRecordingCallback") { audioManager?.unregisterAudioRecordingCallback(recordingCallback) }
        silenced = false
    }

    private fun watch(rec: AudioRecord) {
        bestEffort(TAG, "addOnRoutingChangedListener") { rec.addOnRoutingChangedListener(routingListener, mainHandler) }
    }

    /** Routed somewhere else than the built-in mic (a headset was plugged in): ask for the built-in one again. */
    private fun keepBuiltIn(rec: AudioRecord?) {
        if (rec == null || rec !== currentRecord) return
        val builtIn = builtInMic() ?: return
        if (rec.routedDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_MIC) return
        bestEffort(TAG, "setPreferredDevice") { rec.setPreferredDevice(builtIn) }
    }

    private fun builtInMic(): AudioDeviceInfo? =
        runCatching {
            audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        }.getOrNull()

    override fun noteLevel(
        buffer: FloatArray,
        count: Int,
        startedAtMs: Long,
    ) {
        var peak = 0f
        for (i in 0 until count) {
            val v = abs(buffer[i])
            if (v > peak) peak = v
        }
        peakLevel = peak
        val now = nowMs()
        if (peak > SILENCE_EPSILON) {
            lastAudibleAtMs = now
            noteSilence(false)
        } else {
            val quietSince = if (lastAudibleAtMs == 0L) startedAtMs else lastAudibleAtMs
            if (now - quietSince > SILENCE_GRACE_MS) noteSilence(true)
        }
    }

    /** Called on the pump's thread each time [silenceLikely] changes while capturing. */
    @Volatile
    var onSilenceChanged: ((Boolean) -> Unit)? = null

    private fun noteSilence(likely: Boolean) {
        if (silenceLikely == likely) return
        silenceLikely = likely
        onSilenceChanged?.invoke(likely)
    }

    override fun resetLevel() {
        peakLevel = 0f
        silenceLikely = false
    }

    private fun openRecord(): AudioRecord? {
        val source = preferredSource()
        val builtIn = builtInMic()
        for (rate in LiveInputPolicy.rates) {
            val rec = tryRecordAnyEncoding(source, rate) ?: continue
            // Chosen before the capture starts, so a plugged-in headset mic never replaces it.
            builtIn?.let { device -> bestEffort(TAG, "setPreferredDevice") { rec.setPreferredDevice(device) } }
            sampleRateHz = rate
            unprocessed = source == MediaRecorder.AudioSource.UNPROCESSED
            return rec
        }
        return null
    }

    /** Float samples first, 16-bit if the device refuses them at this [rate]. */
    private fun tryRecordAnyEncoding(
        source: Int,
        rate: Int,
    ): AudioRecord? =
        tryRecord(source, rate, AudioFormat.ENCODING_PCM_FLOAT)
            ?: tryRecord(source, rate, AudioFormat.ENCODING_PCM_16BIT)

    private fun tryRecord(
        source: Int,
        rate: Int,
        encoding: Int,
    ): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, encoding)
        if (min <= 0) return null
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) FLOAT_BYTES else SHORT_BYTES
        val rec =
            runCatching {
                @Suppress("MissingPermission")
                AudioRecord(
                    source,
                    rate,
                    AudioFormat.CHANNEL_IN_MONO,
                    encoding,
                    (min * BUFFER_MULTIPLIER).coerceAtLeast(LiveInputPolicy.READ_FRAMES * bytesPerSample * BUFFER_MULTIPLIER),
                )
            }.getOrNull()
        if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) return rec
        bestEffort(TAG, "rec?.release()") { rec?.release() }
        return null
    }

    private fun preferredSource(): Int {
        val supported =
            runCatching {
                audioManager?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            }.getOrNull()
        return if (supported == "true") {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }
    }

    private companion object {
        const val FLOAT_BYTES = 4
        const val SHORT_BYTES = 2
    }
}

private const val TAG = "MicCapture"
