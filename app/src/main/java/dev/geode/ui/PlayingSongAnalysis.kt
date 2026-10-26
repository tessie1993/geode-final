package dev.geode.ui

import android.app.Application
import android.net.Uri
import dev.geode.analysis.AnalysisCache
import dev.geode.analysis.AnalysisProfile
import dev.geode.analysis.BeatTuning
import dev.geode.analysis.FeatureTimeline
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.currentQueue
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.geodeContainer
import dev.geode.playback.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The playing song's whole-song analysis (ADR-0001), for the Timeline's auto-cut (#318), when the
 * phone already has one: a Render of the song keeps its analysis in the cache. Playing a song never
 * analyses it. Follows Playback so a new song reads its own, and drops it when nothing plays.
 */
@Singleton
// Playback's and Settings' Command and Fact are in/out, so Kotlin gives the store parameters Java
// wildcards the provided stores lack; without this Dagger finds no binding for them.
@JvmSuppressWildcards
class PlayingSongAnalysis internal constructor(
    private val application: Application,
    private val playback: Playback,
    private val settings: Settings,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(application: Application, playback: Playback, settings: Settings) :
        this(application, playback, settings, application.geodeContainer.appScope)

    /** The playing song's analysis, or null while it has none or nothing plays. */
    @Volatile
    var timeline: FeatureTimeline? = null
        private set

    init {
        scope.launch {
            playback.state
                .map {
                    it.player.currentQueue
                        ?.current
                        ?.id
                }.distinctUntilChanged()
                .collect { loadFor(it?.toUri()) }
        }
    }

    private fun loadFor(uri: Uri?) {
        timeline = null
        if (uri == null) return
        scope.launch {
            val found = withContext(Dispatchers.IO) { cached(uri) }
            if (currentUri == uri) timeline = found
        }
    }

    /** The song's analysis as a Render keeps it, or as an older version of the app kept it. */
    private fun cached(uri: Uri): FeatureTimeline? {
        val analysis = (settings.state.value as? SettingsState.Ready)?.values?.analysis
        val sensitivity = analysis?.beatSensitivity ?: BeatTuning.SENSITIVITY_DEFAULT
        val gap = BeatTuning.clampIntervalMs(analysis?.beatMinIntervalMs ?: BeatTuning.INTERVAL_MS_DEFAULT)
        return AnalysisCache.load(application, uri, sensitivity, gap, AnalysisProfile.RENDER)
            ?: AnalysisCache.load(application, uri, sensitivity, gap, AnalysisProfile.LIVE)
    }

    private val currentUri: Uri? get() =
        playback.state.value.player.currentQueue
            ?.current
            ?.id
            ?.toUri()
}
