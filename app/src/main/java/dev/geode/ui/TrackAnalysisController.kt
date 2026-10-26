package dev.geode.ui

import android.app.Application
import android.net.Uri
import dev.geode.analysis.AnalysisCache
import dev.geode.analysis.FeatureTimeline
import dev.geode.analysis.OfflineAnalyzer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A song's whole-file analysis for what exports and renders need (ADR-0001), cached per song.
 * Playing a song never analyses it: only an export asks, through [analyzeCached].
 */
class TrackAnalysisController internal constructor(
    private val application: Application,
    private val host: Host,
) {
    interface Host {
        val guiPrefs: GuiPrefs
    }

    private val offlineAnalyzer = OfflineAnalyzer(application)

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
        // At the cache's precision, so the export draws the same whether the analysis is fresh or cached (#302).
        return AnalysisCache
            .stored(offlineAnalyzer.analyze(uri, gui.beatSensitivity, gui.effectiveBeatMinIntervalMs, onProgress))
            .also { AnalysisCache.save(application, uri, it) }
    }
}
