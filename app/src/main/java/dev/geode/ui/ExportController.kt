package dev.geode.ui

import android.app.Application
import android.net.Uri
import dev.geode.analysis.FeatureTimeline
import dev.geode.data.ExportDefaults
import dev.geode.engine.bridge.CustomizationSnapshot
import dev.geode.export.DriftStop
import dev.geode.export.ExportAspect
import dev.geode.export.ExportCodec
import dev.geode.export.ExportRange
import dev.geode.export.ExportRun
import dev.geode.export.ExportService
import dev.geode.export.LongFormAudio
import dev.geode.export.LoopExtend
import dev.geode.export.LoopRender
import dev.geode.export.LoopSpec
import dev.geode.export.LoudnessAdvice
import dev.geode.export.LoudnessTarget
import dev.geode.export.MixClip
import dev.geode.export.TimeOfDayDrift
import dev.geode.export.VideoExporter
import dev.geode.render.SceneFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExportUiState(
    val customDestination: Boolean = false,
    val phase: ExportPhase = ExportPhase.Idle,
    /**
     * How the last completed render's loudness compared to the persisted loudness-target default,
     * or null before a render finishes (or if it could not be measured). Geode does not yet apply
     * the resulting gain, so this is a readout, not a correction — see [ExportController.startExport].
     */
    val loudnessAdvice: LoudnessAdvice? = null,
)

/** Progress and result of a loop render + long-form extend, mirroring [ExportUiState]. */
data class LoopUiState(
    val phase: ExportPhase = ExportPhase.Idle,
)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ExportController internal constructor(
    private val application: Application,
    private val scope: CoroutineScope,
    private val host: Host,
    // Where the dialog's tempo analysis runs; a test runs it in place.
    private val analysisDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    interface Host {
        val exportUri: Uri?

        var cachedTimeline: FeatureTimeline?

        suspend fun analyze(
            uri: Uri,
            onProgress: (Float) -> Unit,
        ): FeatureTimeline

        val guiPrefs: GuiPrefs

        /** What new renders start with, from Settings. */
        val exportDefaults: ExportDefaults

        /**
         * The look a render draws: the current Customization moved by [drift], as the engine reads it
         * (#337). The open editor's scene keyframes no longer animate a render: the Customization's plan
         * sets every parameter they did, and its lanes, Modulators and Reactions move a look over time.
         */
        fun look(drift: DriftStop): CustomizationSnapshot
    }

    private val exporter = VideoExporter(application)

    private val _exportState = MutableStateFlow(ExportUiState())
    val exportState: StateFlow<ExportUiState> = _exportState

    @Volatile
    private var exportCancelled = false

    private var exportJob: Job? = null

    init {
        if (ExportRun.running) {
            scope.launch {
                ExportRun.state
                    .takeWhile { it.running }
                    .collect { run ->
                        _exportState.update { it.copy(phase = ExportPhase.Running(run.progress ?: 0f)) }
                    }
                if (!ExportRun.running) _exportState.value = ExportUiState()
            }
        }
    }

    private var tempoJob: Job? = null

    /**
     * Makes sure the current song's export analysis (ADR-0001) is there, for the export dialog: its
     * loop-safe choice needs the song's tempo, which only that analysis gives (the Visual reacts live and
     * knows none). Uses the cached analysis when there is one; the tempo shows once it is known.
     */
    @Suppress("TooGenericExceptionCaught") // An analysis that fails leaves the tempo unknown, as before.
    fun prepareTempo() {
        val uri = host.exportUri ?: return
        if (host.cachedTimeline != null || tempoJob?.isActive == true) return
        tempoJob =
            scope.launch(analysisDispatcher) {
                try {
                    val analysed = host.analyze(uri) {}
                    if (host.exportUri == uri && host.cachedTimeline == null) host.cachedTimeline = analysed
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failed: Exception) {
                    dev.geode.RingLog.note(TAG, "the export analysis for the tempo failed", failed)
                }
            }
    }

    fun startExport(
        aspect: ExportAspect,
        fps: Int,
        sceneFactory: SceneFactory,
        destination: Uri? = null,
        loopSafe: Boolean = false,
        range: ExportRange? = null,
        codec: ExportCodec = ExportCodec.H264,
    ) {
        val uri = host.exportUri ?: return
        if (_exportState.value.phase.isBusy || ExportRun.running) return
        exportCancelled = false
        _exportState.value =
            ExportUiState(customDestination = destination != null, phase = ExportPhase.Running(0f))
        // Basename only: a SAF document id is "primary:Music/Artist/Song.mp3", and this
        // label goes straight into the foreground-service notification, where the lock
        // screen and every enabled notification listener can read it.
        ExportRun.begin(uri.lastPathSegment.orEmpty().substringAfterLast('/'))
        ExportService.start(application)
        exportJob =
            ExportRun.scope.launch(Dispatchers.Default) {
                try {
                    val analysed =
                        host.cachedTimeline ?: host
                            .analyze(uri) { p ->
                                _exportState.update { it.copy(phase = ExportPhase.Running(p * 0.2f)) }
                                ExportRun.publish(p * 0.2f)
                            }.also { if (host.exportUri == uri) host.cachedTimeline = it }
                    val gui = host.guiPrefs
                    val t =
                        analysed.withBeatSensitivity(
                            gui.beatSensitivity,
                            gui.effectiveBeatMinIntervalMs,
                        )
                    val name = "geode_${System.currentTimeMillis()}.mp4"
                    val job =
                        VideoExporter.Job(
                            audioUri = uri,
                            timeline = t,
                            sceneFactory = sceneFactory,
                            look = host.look(DriftStop.NONE),
                            aspect = aspect,
                            fps = fps,
                            range = range,
                            loopSafe = loopSafe,
                            codec = codec,
                        )
                    val result =
                        exporter.export(
                            job = job,
                            fileName = name,
                            destination = destination,
                            loudnessTarget = defaultLoudnessTarget(),
                            onProgress = { p ->
                                val overall = 0.2f + p * 0.8f
                                _exportState.update { it.copy(phase = ExportPhase.Running(overall)) }
                                ExportRun.publish(overall)
                            },
                            isCancelled = { exportCancelled || ExportRun.cancelRequested },
                        )
                    _exportState.value =
                        ExportUiState(
                            customDestination = destination != null,
                            phase = result.toPhase(),
                            loudnessAdvice = (result as? VideoExporter.Result.Saved)?.loudnessAdvice,
                        )
                } catch (t: Throwable) {
                    // Cancellation is tested before the user's own cancel flag: when the two
                    // coincide the flag branch used to win and swallow the CancellationException,
                    // leaving a cancelled coroutine to finish as if it had succeeded.
                    if (t is kotlinx.coroutines.CancellationException) {
                        _exportState.value = ExportUiState()
                        throw t
                    } else if (exportCancelled) {
                        _exportState.value = ExportUiState()
                    } else {
                        val detail = "${t.javaClass.simpleName}: ${t.message ?: "no message"}"
                        _exportState.value = ExportUiState(phase = ExportPhase.Failed(detail))
                    }
                } finally {
                    ExportRun.finish()
                }
            }
    }

    fun cancelExport() {
        exportCancelled = true
    }

    fun resetExportState() {
        if (!_exportState.value.phase.isBusy) _exportState.value = ExportUiState()
    }

    // The main export's UI (SettingsDialog) has no path to this controller's public API for
    // per-render options that aren't already threaded through startExport's callers, so the
    // loudness target rides along as a persisted default instead.
    private fun defaultLoudnessTarget(): LoudnessTarget = LoudnessTarget.byId(host.exportDefaults.loudnessTargetId)

    private val loopRenderer = LoopRender(application)
    private val loopExtender = LoopExtend(application)

    private val _loopState = MutableStateFlow(LoopUiState())
    val loopState: StateFlow<LoopUiState> = _loopState

    @Volatile
    private var loopCancelled = false

    private var loopJob: Job? = null

    /**
     * Renders a seamless loop from the current track's analysis, then repeats it into a
     * long-form video with [audioClips] (or, if empty, the current track) as its soundtrack.
     *
     * Runs on [ExportRun.scope] and shares [ExportRun]'s single-render guard and foreground
     * notification with [startExport]: a long-form render is exactly the case that must survive
     * the Activity going away, and the two are heavy enough that they should not overlap.
     */
    fun startLoopRender(
        aspect: ExportAspect,
        codec: ExportCodec,
        fps: Int,
        sceneFactory: SceneFactory,
        loopMs: Long,
        crossfadeMs: Long,
        drift: TimeOfDayDrift,
        audioClips: List<Uri>,
        destination: Uri? = null,
    ) {
        val uri = host.exportUri ?: return
        if (_loopState.value.phase.isBusy || ExportRun.running) return
        loopCancelled = false
        _loopState.value = LoopUiState(phase = ExportPhase.Running(0f))
        ExportRun.begin(uri.lastPathSegment.orEmpty().substringAfterLast('/'))
        ExportService.start(application)
        loopJob =
            ExportRun.scope.launch(Dispatchers.Default) {
                try {
                    val analysed =
                        host.cachedTimeline ?: host
                            .analyze(uri) { p ->
                                publishLoopProgress(p * ANALYSIS_SPAN)
                            }.also { if (host.exportUri == uri) host.cachedTimeline = it }
                    val spec = LoopSpec.of(loopMs, crossfadeMs, fps = fps, bpm = analysed.bpm)
                    val renderResult =
                        loopRenderer.render(
                            timeline = analysed,
                            sceneFactory = sceneFactory,
                            aspect = aspect,
                            spec = spec,
                            look = host::look,
                            drift = drift,
                            codec = codec,
                            onProgress = { p -> publishLoopProgress(ANALYSIS_SPAN + p * RENDER_SPAN) },
                            isCancelled = { loopCancelled || ExportRun.cancelRequested },
                        )
                    _loopState.value = LoopUiState(phase = finishLoopRender(renderResult, uri, audioClips, destination))
                } catch (t: Throwable) {
                    if (t is kotlinx.coroutines.CancellationException) {
                        _loopState.value = LoopUiState()
                        throw t
                    } else if (loopCancelled) {
                        _loopState.value = LoopUiState()
                    } else {
                        val detail = "${t.javaClass.simpleName}: ${t.message ?: "no message"}"
                        _loopState.value = LoopUiState(phase = ExportPhase.Failed(detail))
                    }
                } finally {
                    ExportRun.finish()
                }
            }
    }

    /** Extends a rendered reel into the long-form file, or passes through a render failure. */
    private suspend fun finishLoopRender(
        renderResult: LoopRender.Result,
        trackUri: Uri,
        audioClips: List<Uri>,
        destination: Uri?,
    ): ExportPhase =
        when (renderResult) {
            is LoopRender.Result.Failed -> ExportPhase.Failed(renderResult.message)
            LoopRender.Result.Cancelled -> ExportPhase.Idle
            is LoopRender.Result.Rendered -> {
                val reel = renderResult.reel
                try {
                    val audio =
                        if (audioClips.isEmpty()) {
                            LongFormAudio.SingleTrack(MixClip(trackUri, trackUri.lastPathSegment.orEmpty().substringAfterLast('/')))
                        } else {
                            LongFormAudio.Mix(
                                audioClips.map { clip ->
                                    MixClip(clip, clip.lastPathSegment.orEmpty().substringAfterLast('/'))
                                },
                            )
                        }
                    val name = "geode_loop_${System.currentTimeMillis()}.mp4"
                    val extended =
                        loopExtender.extend(
                            reel = reel,
                            audio = audio,
                            fileName = name,
                            destination = destination,
                            onProgress = { p -> publishLoopProgress(ANALYSIS_SPAN + RENDER_SPAN + p * EXTEND_SPAN) },
                            isCancelled = { loopCancelled || ExportRun.cancelRequested },
                        )
                    when (extended) {
                        is LoopExtend.Result.Saved -> ExportPhase.Done(extended.uri)
                        is LoopExtend.Result.Failed -> ExportPhase.Failed(extended.message)
                        LoopExtend.Result.Cancelled -> ExportPhase.Idle
                    }
                } finally {
                    reel.delete()
                }
            }
        }

    private fun publishLoopProgress(overall: Float) {
        val clamped = overall.coerceIn(0f, 1f)
        _loopState.update { it.copy(phase = ExportPhase.Running(clamped)) }
        ExportRun.publish(clamped)
    }

    fun cancelLoopRender() {
        loopCancelled = true
    }

    fun clearLoopResult() {
        if (!_loopState.value.phase.isBusy) _loopState.value = LoopUiState()
    }

    private companion object {
        const val TAG = "Export"

        // Analysing the track is quick against the render itself; extending mostly copies
        // already-encoded samples, so it gets less of the bar than the GPU render does.
        const val ANALYSIS_SPAN = 0.1f
        const val RENDER_SPAN = 0.6f
        const val EXTEND_SPAN = 1f - ANALYSIS_SPAN - RENDER_SPAN
    }
}
