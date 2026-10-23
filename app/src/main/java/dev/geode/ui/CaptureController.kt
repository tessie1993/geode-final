package dev.geode.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import dev.geode.audio.MicCapture
import dev.geode.engine.types.Hz
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class MicState(
    val active: Boolean = false,
    val failure: MicCapture.Failure? = null,
)

internal class CaptureController(
    application: Application,
    capture: dev.geode.engine.audio.PcmSink,
    private val host: Host,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    interface Host {
        fun pausePlayback()

        fun resetAnalysis()

        fun setAnalysisRate(rate: Hz)

        fun setMicReactivePref(on: Boolean)

        /** The persisted "mic was on" preference, read once on [start] to restore capture. */
        val micReactivePref: Boolean
    }

    private val micCapture =
        MicCapture(application, capture).apply {
            // A capture that ends by itself (a read failure, the input gone) is noticed when it
            // happens, on the main thread, instead of by polling (#17).
            onEnded = { mainHandler.post { refreshMicState() } }
        }

    private val _micState = MutableStateFlow(MicState())

    val micState: StateFlow<MicState> = _micState

    val micActive: Boolean get() = micCapture.active

    @Volatile
    private var tapSampleRateHz: Int = 0

    val audioFormatHook: (sampleRateHz: Int, channelCount: Int, encoding: Int) -> Unit =
        { rate, _, _ ->
            tapSampleRateHz = rate
            if (!micCapture.active) host.setAnalysisRate(Hz(rate))
        }

    /**
     * Restores a mic-reactive session.
     *
     * Deliberately not an `init` block: turning the mic back on calls into the owning session,
     * whose later fields — `player` among them — are not assigned while this is being built. The
     * owner calls this once it is fully constructed instead.
     */
    fun start() {
        // Restore a mic-reactive session across process death. The pref was written on every
        // mic toggle but never read back, so a restart silently dropped it. Never request the
        // permission here: if it has since been revoked, leave the pref as-is and stay silent —
        // the person can re-enable it from the mic control, which will prompt as usual.
        if (host.micReactivePref && !micCapture.active && hasMicPermission()) {
            setMicEnabled(true)
        }
    }

    private fun playbackRateFallback(): Hz = Hz(tapSampleRateHz.takeIf { it > 0 } ?: 44100)

    fun setMicEnabled(enabled: Boolean): MicCapture.Failure? {
        if (!enabled) {
            micCapture.stop()
            host.resetAnalysis()
            host.setAnalysisRate(playbackRateFallback())
            _micState.value = MicState(active = false)
            host.setMicReactivePref(false)
            return null
        }
        if (micCapture.active) return null
        host.pausePlayback()
        val failure = micCapture.start { rate -> host.setAnalysisRate(Hz(rate)) }
        if (failure != null) {
            _micState.value = MicState(active = false, failure = failure)
            return failure
        }
        host.resetAnalysis()
        _micState.value = MicState(active = true)
        host.setMicReactivePref(true)
        return null
    }

    fun hasMicPermission(): Boolean = micCapture.hasPermission()

    fun refreshMicState() {
        if (!_micState.value.active || micCapture.active) return
        host.resetAnalysis()
        host.setAnalysisRate(playbackRateFallback())
        _micState.value = MicState(active = false, failure = MicCapture.Failure.UNAVAILABLE)
        host.setMicReactivePref(false)
    }

    fun shutdown() {
        micCapture.stop()
    }
}
