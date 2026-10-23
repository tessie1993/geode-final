package dev.geode.ui

import android.app.Application
import android.net.Uri
import dev.geode.analysis.AnalysisCache
import dev.geode.analysis.FeatureTimeline
import dev.geode.analysis.OfflineAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A song's whole-file analysis for what exports and renders need (ADR-0001), cached per song, and the
 * waveform drawn from it. Playing a song never analyses it: only an export asks, through [analyzeCached].
 */
internal class TrackAnalysisController(
    private val application: Application,
    private val host: Host,
) {
    interface Host {
        val guiPrefs: GuiPrefs
        val vizState: MutableStateFlow<VizUiState>
    }

    private val offlineAnalyzer = OfflineAnalyzer(application)

    private val _waveform = MutableStateFlow<FloatArray?>(null)
    val waveform: StateFlow<FloatArray?> = _waveform

    private var timelineBacking: FeatureTimeline? = null

    private val _tempo = MutableStateFlow(0f)

    /**
     * The current song's tempo from its whole-song export analysis (ADR-0001), 0 until that is known.
     * What the export's loop-safe choice and bar trim go by; never the live Visual's.
     */
    val tempo: StateFlow<Float> = _tempo

    var timeline: FeatureTimeline?
        get() = timelineBacking
        set(value) {
            timelineBacking = value
            _waveform.value = value?.let(::waveformOf)
            _tempo.value = value?.bpm ?: 0f
        }

    suspend fun analyzeCached(
        uri: Uri,
        onProgress: (Float) -> Unit,
    ): FeatureTimeline {
        val gui = host.guiPrefs
        AnalysisCache
            .load(application, uri, gui.beatSensitivity, gui.effectiveBeatMinIntervalMs)
            ?.let {
                onProgress(1f)
                return it
            }
        return offlineAnalyzer
            .analyze(uri, gui.beatSensitivity, gui.effectiveBeatMinIntervalMs, onProgress)
            .also { AnalysisCache.save(application, uri, it) }
    }

    @Suppress("ReturnCount")
    private fun waveformOf(timeline: FeatureTimeline): FloatArray? {
        val frames = timeline.frames
        if (frames.size < WAVEFORM_BUCKETS) return null
        val out = FloatArray(WAVEFORM_BUCKETS)
        var peak = 0f
        for (b in 0 until WAVEFORM_BUCKETS) {
            val from = b * frames.size / WAVEFORM_BUCKETS
            val to = ((b + 1) * frames.size / WAVEFORM_BUCKETS).coerceAtMost(frames.size)
            var max = 0f
            for (i in from until to) {
                val v = frames[i].features.rms
                if (v > max) max = v
            }
            out[b] = max
            if (max > peak) peak = max
        }
        if (peak <= 0f) return null
        for (i in out.indices) out[i] = out[i] / peak
        return out
    }

    private companion object {
        const val WAVEFORM_BUCKETS = 240
    }
}
