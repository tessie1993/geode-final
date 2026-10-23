package dev.geode.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import dev.geode.analysis.FeatureTimeline
import dev.geode.audio.MicCapture
import dev.geode.data.EditorProjectStore
import dev.geode.data.ExportDefaults
import dev.geode.data.PlayerPrefs
import dev.geode.engine.types.Hz
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.export.ExportAspect
import dev.geode.export.ExportCodec
import dev.geode.export.ExportRange
import dev.geode.export.StudioClip
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.geodeContainer
import dev.geode.playback.EngineFailureNotices
import dev.geode.playback.PlaybackEngine
import dev.geode.playback.toUri
import dev.geode.render.AdsrConfig
import dev.geode.render.LfoConfig
import dev.geode.render.SceneFactory
import dev.geode.render.scene.SceneParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What is left of the old app's hub until the switch-over (#17) finishes: the mic, the old Settings
 * bridges and Studio. The Library, Visuals and Customization are their own app-scoped stores (#17);
 * the live Visual reads its sound straight from the engine.
 *
 * Playback is no longer here. The music, the queue and the history belong to the app-scoped
 * [Playback] store; this follows it for what the Visual and the old screens need (the current song
 * for its analysis, lyrics and exports) and sends it Commands (a capture pausing the music).
 */
@OptIn(UnstableApi::class)
class PlayerSession internal constructor(
    private val application: Application,
    private val playback: Playback,
    private val oldScreens: OldScreenPlayback,
    private val oldSettings: SettingsForOldScreens,
    private val customization: CustomizationFeature,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val engineSession = PlaybackEngine.acquireForUi(application)

    private val engine = engineSession.analysis

    @OptIn(ExperimentalCoroutinesApi::class)
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val container = application.geodeContainer

    private val captureController: CaptureController =
        CaptureController(
            application,
            engineSession.captureSink,
            object : CaptureController.Host {
                override fun pausePlayback() = playback.send(PlaybackCommand.Pause)

                override fun resetAnalysis() {
                    engineSession.sampleRing.beginEpoch()
                    engine.reset()
                }

                override fun setAnalysisRate(rate: Hz) {
                    engine.sampleRateHz = rate.value
                }

                override fun setMicReactivePref(on: Boolean) {
                    setGuiPrefs(guiPrefs.value.copy(micReactive = on))
                }

                override val micReactivePref: Boolean get() = guiPrefs.value.micReactive
            },
        )

    fun setMicEnabled(enabled: Boolean): MicCapture.Failure? = captureController.setMicEnabled(enabled)

    fun hasMicPermission(): Boolean = captureController.hasMicPermission()

    init {
        engineSession.onAudioFormat = captureController.audioFormatHook
    }

    private val settings: PlayerSettingsController =
        PlayerSettingsController(oldSettings, container.appScope)

    val exportState: StateFlow<ExportUiState> get() = exportController.exportState

    val userDataLoaded: StateFlow<Boolean> get() = oldSettings.loaded

    val guiPrefs: StateFlow<GuiPrefs> get() = settings.guiPrefs

    val playerPrefs: StateFlow<PlayerPrefs> get() = settings.playerPrefs

    fun setGuiPrefs(prefs: GuiPrefs) = settings.setGuiPrefs(prefs)

    fun setPlayerPrefs(prefs: PlayerPrefs) = settings.setPlayerPrefs(prefs)

    /** The song Playback has loaded, read from its state: Playback owns the queue (ADR-0004). */
    private val currentUri: Uri? get() = oldScreens.currentTrack()?.toUri()

    private val analysis: TrackAnalysisController =
        TrackAnalysisController(
            application,
            object : TrackAnalysisController.Host {
                override val guiPrefs: GuiPrefs get() = settings.guiPrefs.value
            },
        )

    val waveform: StateFlow<FloatArray?> get() = analysis.waveform

    private val _lyrics = MutableStateFlow<Lyrics?>(null)

    val lyrics: StateFlow<Lyrics?> = _lyrics

    private fun loadLyricsFor(uri: Uri?) {
        _lyrics.value = null
        if (uri == null) return
        scope.launch(Dispatchers.IO) {
            val found = LyricsLoader.load(application, uri)
            withContext(Dispatchers.Main) { if (currentUri == uri) _lyrics.value = found }
        }
    }

    fun currentTrackUri(): TrackId? = oldScreens.currentTrack()

    /**
     * A new song: the Visual starts listening afresh and its lyrics are looked up. The song is never
     * analysed here: the Visual reacts to the live sound only (owner rule, #17). An export analyses
     * the song it renders, when it renders it, so the last song's analysis is dropped.
     */
    private fun onTrackChanged() {
        engine.reset()
        analysis.timeline = null
        loadLyricsFor(currentUri)
    }

    private val _playbackNotice = MutableStateFlow<String?>(null)

    val playbackNotice: StateFlow<String?> = _playbackNotice

    // Failures caught at the native bridge reach the same app-wide banner (ADR-0004).
    private val engineFailureNotices =
        EngineFailureNotices(show = { message -> scope.launch { _playbackNotice.value = application.getString(message) } })

    fun clearPlaybackNotice() {
        _playbackNotice.value = null
    }

    private val exportController: ExportController =
        ExportController(
            application,
            scope,
            object : ExportController.Host {
                override val exportUri: Uri? get() = currentUri
                override var cachedTimeline: FeatureTimeline?
                    get() = analysis.timeline
                    set(value) {
                        analysis.timeline = value
                    }

                override suspend fun analyze(
                    uri: Uri,
                    onProgress: (Float) -> Unit,
                ): FeatureTimeline = analysis.analyzeCached(uri, onProgress)

                override val guiPrefs: GuiPrefs get() = settings.guiPrefs.value
                override val exportDefaults: ExportDefaults get() = oldSettings.exportDefaults()
                override val sceneParams get() = currentSceneParams()

                override fun lfoConfigs() = emptyList<LfoConfig>()

                override fun adsrConfigs() = emptyList<AdsrConfig>()

                override fun keyframes() = editor.state.value.project.keyframes
            },
        )

    val studio: StateFlow<StudioUiState> get() = exportController.studio

    internal val editor: EditorController = EditorController(EditorProjectStore(application), scope, storeScope).also { it.open() }

    fun analysisTimeline(): FeatureTimeline? = analysis.timeline

    /** The current song's tempo from its export analysis, 0 until known (see [prepareExportTempo]). */
    val exportTempo: StateFlow<Float> get() = analysis.tempo

    /** Has the export analysis worked out, for the export dialog, if it is not there yet. */
    fun prepareExportTempo() = exportController.prepareTempo()

    /** The Style the Visual shows: Customization's (#17). */
    fun currentSceneId(): String = customization.state.value.current.style.value

    /**
     * What exports draw with: the Style's defaults. The Visual itself is driven by Customization's plan,
     * which the export path does not read yet.
     */
    fun currentSceneParams(): SceneParams = SceneParams.DEFAULT

    /**
     * Where the song is now while it plays, null otherwise: read from Playback's state and the clock,
     * never from a screen's flow, which stops following Playback when nothing watches it.
     */
    fun playbackPositionMs(): Long? {
        val player = playback.state.value.player
        if (!OldScreenModels.meantToPlay(player)) return null
        return OldScreenModels.positionAt(player, Millis(SystemClock.elapsedRealtime())).value
    }

    fun trackDurationMs(): Long =
        OldScreenModels
            .queueOf(playback.state.value.player)
            ?.current
            ?.duration
            ?.value ?: 0L

    val userDataRepository: UserDataRepository get() = oldSettings

    fun startExport(
        aspect: ExportAspect,
        fps: Int,
        sceneFactory: SceneFactory,
        destination: Uri? = null,
        loopSafe: Boolean = false,
        range: ExportRange? = null,
        codec: ExportCodec = ExportCodec.H264,
    ) = exportController.startExport(aspect, fps, sceneFactory, destination, loopSafe, range, codec)

    fun cancelExport() = exportController.cancelExport()

    fun resetExportState() = exportController.resetExportState()

    fun refreshStudioClips() = exportController.refreshStudioClips()

    fun deleteStudioClip(
        uri: String,
        onResult: (Boolean) -> Unit,
    ) = exportController.deleteStudioClip(uri, onResult)

    fun renameStudioClip(
        uri: String,
        name: String,
        onResult: (Boolean) -> Unit,
    ) = exportController.renameStudioClip(uri, name, onResult)

    fun describeStudioClip(
        uri: Uri,
        onReady: (StudioClip) -> Unit,
    ) = exportController.describeStudioClip(uri, onReady)

    fun startStudioExport(
        clip: StudioClip,
        edit: dev.geode.export.ClipEdit,
        destination: Uri? = null,
    ) = exportController.startStudioExport(clip, edit, destination)

    fun cancelStudioExport() = exportController.cancelStudioExport()

    fun startProjectExport(destination: Uri? = null) = exportController.startProjectExport(editor.state.value.project, destination)

    fun clearStudioResult() = exportController.clearStudioResult()

    val loopState: StateFlow<LoopUiState> get() = exportController.loopState

    fun startLoopRender(
        aspect: ExportAspect,
        codec: ExportCodec,
        fps: Int,
        sceneFactory: SceneFactory,
        loopMs: Long,
        crossfadeMs: Long,
        drift: dev.geode.export.TimeOfDayDrift,
        audioClips: List<Uri>,
        destination: Uri? = null,
    ) = exportController.startLoopRender(aspect, codec, fps, sceneFactory, loopMs, crossfadeMs, drift, audioClips, destination)

    fun cancelLoopRender() = exportController.cancelLoopRender()

    fun clearLoopResult() = exportController.clearLoopResult()

    /**
     * Tears the session down. Called only by [dev.geode.di.PlayerSessionProvider], on behalf of the
     * one ViewModel that owns the session — never from a screen.
     */
    internal fun shutdown() {
        // Queue every outstanding write BEFORE the pending set is snapshotted below, or the store
        // scope's cancellation races the very writes this teardown exists to preserve.
        val pendingStoreWrites =
            storeScope.coroutineContext.job.children
                .toList()

        captureController.shutdown()
        engineFailureNotices.close()
        if (engineSession.onAudioFormat === captureController.audioFormatHook) engineSession.onAudioFormat = null
        PlaybackEngine.releaseUi()
        scope.cancel()

        drainStoreWrites(pendingStoreWrites)
    }

    /**
     * Lets the queued store writes finish, then retires the scope that owns them.
     *
     * This runs on the app scope's IO dispatcher rather than the caller's thread. `shutdown()` is
     * reached from `PlayerViewModel.onCleared()`, which is the main thread: the previous version
     * spent its whole [STORE_FLUSH_BUDGET_MS] budget there, between a blocking `Future.get` and a
     * `runBlocking`, so a slow disk stalled Activity teardown for up to two seconds.
     *
     * The budget and the ordering are unchanged; only the thread that waits them out is different.
     * The trade is that durability past this point is best-effort — the process could in principle
     * be killed mid-drain — but the writes are already queued on a serialised IO dispatcher, and
     * blocking the main thread bought only the window between destroy and death.
     */
    private fun drainStoreWrites(pending: List<Job>) {
        container.appScope.launch(Dispatchers.IO) {
            withTimeoutOrNull(STORE_FLUSH_BUDGET_MS) { pending.joinAll() }
            storeScope.cancel()
        }
    }

    init {
        // Last init block in the class, so every field above is assigned by now — which is what
        // the capture controller needs before it may call back into this session.
        // The mic is turned back on once Settings says whether it was on.
        scope.launch {
            oldSettings.loaded()
            captureController.start()
        }
        followPlayback()
    }

    /**
     * Follows Playback for what the Visual and the old screens need: a new song gets its lyrics, and
     * starting the music ends a live capture (the mic and the music never mix).
     */
    private fun followPlayback() {
        scope.launch {
            playback.state
                .map { OldScreenModels.queueOf(it.player)?.current?.id }
                .distinctUntilChanged()
                .collect { onTrackChanged() }
        }
        scope.launch {
            playback.state
                .map { OldScreenModels.meantToPlay(it.player) }
                .distinctUntilChanged()
                .drop(1)
                .collect { playing -> if (playing && captureController.micActive) setMicEnabled(false) }
        }
    }

    private companion object {
        const val STORE_FLUSH_BUDGET_MS = 2_000L
    }
}
