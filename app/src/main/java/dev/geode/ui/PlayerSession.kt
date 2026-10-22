package dev.geode.ui

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import dev.geode.analysis.AudioFeatures
import dev.geode.analysis.FeatureTimeline
import dev.geode.analysis.IntelligenceMode
import dev.geode.analysis.LiveInputProfile
import dev.geode.audio.AudioBus
import dev.geode.audio.AudioFxState
import dev.geode.audio.MicCapture
import dev.geode.data.EditorProjectStore
import dev.geode.data.ExportDefaults
import dev.geode.data.FavouritesRepository
import dev.geode.data.FilePresetRepository
import dev.geode.data.LfoStore
import dev.geode.data.MilkPackImporter
import dev.geode.data.MilkTexture
import dev.geode.data.PlayerPrefs
import dev.geode.data.Preset
import dev.geode.data.PresetFolders
import dev.geode.data.PresetRepository
import dev.geode.data.PresetStore
import dev.geode.data.SharedPrefsFavouritesRepository
import dev.geode.data.SmartPlaylist
import dev.geode.data.SmartPlaylistMatcher
import dev.geode.data.TagWriteOutcome
import dev.geode.engine.types.Hz
import dev.geode.engine.types.Millis
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.engine.types.TrackId
import dev.geode.export.ExportAspect
import dev.geode.export.ExportCodec
import dev.geode.export.ExportRange
import dev.geode.export.StudioClip
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.geodeContainer
import dev.geode.playback.EngineFailureNotices
import dev.geode.playback.PlaybackEngine
import dev.geode.playback.toUri
import dev.geode.render.AdsrConfig
import dev.geode.render.LfoConfig
import dev.geode.render.SceneFactory
import dev.geode.render.TransitionStyle
import dev.geode.render.scene.CustomizeTab
import dev.geode.render.scene.PcmChunk
import dev.geode.render.scene.SceneParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * What is left of the old app's hub until the switch-over (#17) finishes: the Visual, the old
 * Customize and preset tools, the old Library index, Settings and Studio.
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
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val engineSession = PlaybackEngine.acquireForUi(application)

    private val ring = engineSession.ring

    private val engine = engineSession.analysis

    @OptIn(ExperimentalCoroutinesApi::class)
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val container = application.geodeContainer

    private val prefsFiles = container.prefsFiles

    private val favouritesRepository: FavouritesRepository =
        SharedPrefsFavouritesRepository(prefsFiles.favourites, storeScope)

    private val presetRepository: PresetRepository = FilePresetRepository(PresetStore(application))

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

    val micState: StateFlow<MicState> get() = captureController.micState

    fun setMicEnabled(enabled: Boolean): MicCapture.Failure? = captureController.setMicEnabled(enabled)

    fun hasMicPermission(): Boolean = captureController.hasMicPermission()

    init {
        engineSession.onAudioFormat = captureController.audioFormatHook
    }

    private val presetLibrary: PresetLibraryController =
        PresetLibraryController(
            application,
            presetRepository,
            scope,
            storeScope,
            object : PresetLibraryController.Host {
                override val vizState: StateFlow<VizUiState> get() = _vizState

                override fun updatePresets(transform: (List<Preset>) -> List<Preset>) {
                    _vizState.update { it.copy(presets = transform(it.presets)) }
                }

                override val presetMirrorUri: String? get() = guiPrefs.value.presetMirrorUri
                override val activeMilkPath: String? get() = vizStateStore.activeMilkPath.value
            },
        )

    private val vizStateStore = VizStateStore(prefsFiles.viz, scope)

    private val settings: PlayerSettingsController =
        PlayerSettingsController(oldSettings, container.appScope)

    private val visual: VisualSettingsController =
        VisualSettingsController(
            engine,
            vizStateStore,
            object : VisualSettingsController.Host {
                override val guiPrefs: GuiPrefs get() = settings.guiPrefs.value

                override fun setGuiPrefs(prefs: GuiPrefs) = settings.setGuiPrefs(prefs)

                override fun milkPathFor(preset: Preset): String? = presetLibrary.milkPresetPathFor(preset)
            },
        )

    private val _vizState get() = vizStateStore.state
    val vizState: StateFlow<VizUiState> get() = vizStateStore.state

    init {
        // The beat detection follows Settings (EngineTuning); the reaction speed is the Visual's own.
        engine.attack = _vizState.value.attack
        engine.decay = _vizState.value.decay
    }

    val exportState: StateFlow<ExportUiState> get() = exportController.exportState

    private val musicLibrary = MusicLibraryController(application, prefsFiles.library, scope)

    val library: StateFlow<LibraryState> get() = musicLibrary.library

    val trackOverrides: StateFlow<Map<TrackId, LibraryTrack>> get() = musicLibrary.trackOverrides

    val userDataLoaded: StateFlow<Boolean> get() = oldSettings.loaded

    val guiPrefs: StateFlow<GuiPrefs> get() = settings.guiPrefs

    val playerPrefs: StateFlow<PlayerPrefs> get() = settings.playerPrefs

    val audioFx: StateFlow<AudioFxState> get() = settings.audioFxState

    fun setGuiPrefs(prefs: GuiPrefs) = settings.setGuiPrefs(prefs)

    fun setPlayerPrefs(prefs: PlayerPrefs) = settings.setPlayerPrefs(prefs)

    fun setAudioFxEnabled(enabled: Boolean) = settings.setAudioFxEnabled(enabled)

    fun setAudioFxBand(
        band: Int,
        levelMb: Int,
    ) = settings.setAudioFxBand(band, levelMb)

    fun useAudioFxPreset(index: Int) = settings.useAudioFxPreset(index)

    fun setAudioFxBassBoost(strength: Int) = settings.setAudioFxBassBoost(strength)

    fun setAudioFxLoudness(gainMb: Int) = settings.setAudioFxLoudness(gainMb)

    fun applyLiveInputProfile(profile: LiveInputProfile) = visual.applyLiveInputProfile(profile)

    private val textureController: TextureController =
        TextureController(
            application,
            scope,
            object : TextureController.Host {
                override fun onGeneratedPresetsRemoved(paths: List<String>) {
                    vizStateStore.dropRemovedMilkPaths(paths)
                }
            },
        )

    val textures: StateFlow<List<MilkTexture>> get() = textureController.textures

    private val modulation: ModulationController =
        ModulationController(
            LfoStore(prefsFiles.general),
            prefsFiles.modulation,
            object : ModulationController.Host {
                override val params: SceneParams get() = _vizState.value.params

                override val sceneId: SceneId get() = _vizState.value.sceneId

                override fun setSceneParams(params: SceneParams) = visual.setSceneParams(params)
            },
        )

    val lfos: StateFlow<List<LfoConfig>> get() = modulation.lfos
    val adsrs: StateFlow<List<AdsrConfig>> get() = modulation.adsrs
    val lockedParams: StateFlow<Set<String>> get() = modulation.lockedParams
    val paramHistory: StateFlow<ParamHistoryState> get() = modulation.history
    val abSnapshots: StateFlow<AbSnapshotState> get() = modulation.ab

    fun toggleParamLock(label: String) = modulation.toggleParamLock(label)

    fun randomizeParams(tab: CustomizeTab? = null) = modulation.randomizeParams(tab)

    fun editSceneParams(params: SceneParams) = modulation.editSceneParams(params)

    fun undoParams() = modulation.undo()

    fun redoParams() = modulation.redo()

    fun resetCustomizeTab(tab: CustomizeTab) = modulation.resetTab(tab)

    fun resetAllCustomize() = modulation.resetAll()

    fun captureSnapshotA() = modulation.captureA()

    fun captureSnapshotB() = modulation.captureB()

    fun recallSnapshotA() = modulation.recallA()

    fun recallSnapshotB() = modulation.recallB()

    fun blendSnapshots(t: Float) = modulation.blendAb(t)

    fun setAdsr(
        index: Int,
        config: AdsrConfig,
    ) = modulation.setAdsr(index, config)

    fun setLfo(
        index: Int,
        config: LfoConfig,
    ) = modulation.setLfo(index, config)

    fun importTextures(
        uris: List<Uri>,
        onImported: () -> Unit,
    ) = textureController.importTextures(uris, onImported)

    fun removeTexture(name: String) = textureController.removeTexture(name)

    fun useTexture(
        name: String,
        onReady: (String) -> Unit,
    ) = textureController.useTexture(name, onReady)

    val features: StateFlow<AudioFeatures> = engine.features

    private val pcmScratch = FloatArray(4096)
    private var pcmCursor = 0L

    fun latestPcm(): PcmChunk? {
        val n = ring.copyNewSince(pcmCursor, pcmScratch)
        pcmCursor = ring.lastCopyEndIndex
        return if (n > 0) PcmChunk(pcmScratch, n) else null
    }

    private val milkImport = MilkImportController(application, scope)

    fun milkPresetFilesAsync(onDone: (List<MilkFile>) -> Unit) = milkImport.milkPresetFilesAsync(onDone)

    fun importMilkPresetAsync(
        uri: Uri,
        onDone: (String?) -> Unit,
    ) = milkImport.importMilkPresetAsync(uri, onDone)

    internal fun importMilkPresetBlocking(uri: Uri): String? = milkImport.importMilkPresetBlocking(uri)

    fun milkTextureLinksFor(path: String) = milkImport.textureLinksFor(path)

    fun assignMilkTextureAsync(
        path: String,
        expected: String,
        texture: String?,
        onDone: (List<dev.geode.data.MilkTextureLink>) -> Unit,
    ) = milkImport.assignTextureAsync(path, expected, texture, onDone)

    fun importMilkFolderAsync(
        treeUri: Uri,
        onDone: (MilkPackImporter.Report) -> Unit,
    ) = milkImport.importMilkFolderAsync(treeUri, onDone)

    /** The song Playback has loaded, read from its state: Playback owns the queue (ADR-0004). */
    private val currentUri: Uri? get() = oldScreens.currentTrack()?.toUri()

    val favourites: StateFlow<Set<TrackId>> get() = favouritesRepository.favourites

    private val analysis: TrackAnalysisController =
        TrackAnalysisController(
            application,
            object : TrackAnalysisController.Host {
                override val guiPrefs: GuiPrefs get() = settings.guiPrefs.value
                override val vizState: MutableStateFlow<VizUiState> get() = _vizState
            },
        )

    val waveform: StateFlow<FloatArray?> get() = analysis.waveform

    fun setIntelligenceMode(mode: IntelligenceMode) = analysis.setIntelligenceMode(mode)

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

    val vizApply: SharedFlow<VizApply> get() = visual.vizApply

    val morphFade: SharedFlow<Float> get() = visual.morphFade

    val deviceTracks: StateFlow<List<DeviceTrack>> get() = musicLibrary.deviceTracks

    val mediaRoots: StateFlow<Set<String>> get() = musicLibrary.mediaRoots

    val libraryScanning: StateFlow<Boolean> get() = musicLibrary.libraryScanning

    fun refreshDeviceTracks() = musicLibrary.refreshDeviceTracks()

    fun importTracks(uris: List<Uri>) = musicLibrary.importTracks(uris)

    fun trackOverride(uri: TrackId): LibraryTrack? = musicLibrary.trackOverride(uri)

    suspend fun trackInfoFor(uri: TrackId): LibraryTrack = musicLibrary.trackInfoFor(uri)

    fun saveTrackInfo(
        uri: TrackId,
        title: String,
        artist: String,
        album: String,
        genre: String,
        year: Int,
        trackNo: Int,
        comment: String,
    ) = musicLibrary.saveTrackInfo(uri, title, artist, album, genre, year, trackNo, comment)

    suspend fun writeTrackInfo(
        uri: TrackId,
        title: String,
        artist: String,
        album: String,
        genre: String,
        year: Int,
        trackNo: Int,
        comment: String,
    ): TagWriteOutcome = musicLibrary.writeTrackInfo(uri, title, artist, album, genre, year, trackNo, comment)

    fun importFolder(treeUri: Uri) = musicLibrary.importFolder(treeUri)

    fun removeMediaRoot(uriStr: String) = musicLibrary.removeMediaRoot(uriStr)

    fun rescanMediaRoots() = musicLibrary.rescanMediaRoots()

    fun createMusicPlaylist(name: String) = musicLibrary.createMusicPlaylist(name)

    suspend fun importPlaylistFile(uri: Uri): PlaylistImportResult = musicLibrary.importPlaylistFile(uri)

    fun renameMusicPlaylist(
        oldName: String,
        newName: String,
    ): Boolean = musicLibrary.renameMusicPlaylist(oldName, newName)

    fun moveMusicPlaylistTrack(
        name: String,
        from: Int,
        to: Int,
    ) = musicLibrary.moveMusicPlaylistTrack(name, from, to)

    fun deleteMusicPlaylist(name: String) = musicLibrary.deleteMusicPlaylist(name)

    fun addTrackToPlaylist(
        playlist: String,
        uri: TrackId,
    ) = musicLibrary.addTrackToPlaylist(playlist, uri)

    fun removeTrackFromPlaylist(
        playlist: String,
        uri: TrackId,
    ) = musicLibrary.removeTrackFromPlaylist(playlist, uri)

    fun playPlaylist(
        playlist: String,
        startIndex: Int = 0,
    ) {
        val uris =
            library.value.playlists
                .firstOrNull { it.name == playlist }
                ?.trackUris
                .orEmpty()
        if (uris.isEmpty()) return
        val byUri = library.value.tracks.associateBy { it.uri }
        val tracks = uris.map { u -> byUri[u]?.let(PlaybackQueue::queueTrack) ?: QueueTrack(u) }
        oldScreens.playFrom(tracks, uris[startIndex.coerceIn(0, uris.size - 1)])
    }

    fun saveSmartPlaylist(playlist: SmartPlaylist) = musicLibrary.saveSmartPlaylist(playlist)

    fun deleteSmartPlaylist(name: String) = musicLibrary.deleteSmartPlaylist(name)

    /** A smart playlist's songs; "play count" counts the plays in Playback's history (#146 replaces this). */
    fun resolveSmartPlaylist(playlist: SmartPlaylist): List<DeviceTrack> {
        val plays =
            playback.state.value.history
                .groupingBy { it.track.id }
                .eachCount()
        return SmartPlaylistMatcher.resolve(playlist, deviceTracks.value, { plays[it] ?: 0 }, favourites.value)
    }

    fun playSmartPlaylist(playlist: SmartPlaylist) {
        val tracks = resolveSmartPlaylist(playlist).map(PlaybackQueue::queueTrack)
        if (tracks.isNotEmpty()) oldScreens.playFrom(tracks, tracks.first().uri)
    }

    fun currentTrackUri(): TrackId? = oldScreens.currentTrack()

    fun toggleFavourite(uri: TrackId? = null) {
        val track = uri ?: currentTrackUri() ?: return
        storeScope.launch { favouritesRepository.toggle(track) }
    }

    val presetFolders: StateFlow<PresetFolders> get() = presetLibrary.folders

    fun addPresetFolder(path: String) = presetLibrary.addPresetFolder(path)

    fun renamePresetFolder(
        from: String,
        to: String,
    ) = presetLibrary.renamePresetFolder(from, to)

    fun movePresetToFolder(
        name: PresetId,
        folder: String,
    ) = presetLibrary.movePresetToFolder(name, folder)

    fun userMilkPresets(): List<File> = presetLibrary.userMilkPresets()

    fun milkCatalog(): dev.geode.data.MilkCatalog = presetLibrary.milkCatalog()

    /**
     * A new song: the Visual starts listening afresh and its lyrics are looked up. The song is never
     * analysed here: the Visual reacts to the live sound only (owner rule, #17). An export analyses
     * the song it renders, when it renders it, so the last song's analysis is dropped.
     */
    private fun onTrackChanged() {
        engine.reset()
        analysis.timeline = null
        loadLyricsFor(currentUri)
        _vizState.update { it.copy(suggestedSceneId = null, bpm = 0f) }
    }

    private val _playbackNotice = MutableStateFlow<String?>(null)

    val playbackNotice: StateFlow<String?> = _playbackNotice

    // Failures caught at the native bridge reach the same app-wide banner (ADR-0004).
    private val engineFailureNotices =
        EngineFailureNotices(show = { message -> scope.launch { _playbackNotice.value = application.getString(message) } })

    fun clearPlaybackNotice() {
        _playbackNotice.value = null
    }

    fun selectScene(sceneId: SceneId) = visual.selectScene(sceneId)

    fun setReactivity(
        attack: Float,
        decay: Float,
    ) = visual.setReactivity(attack, decay)

    fun setSceneParams(params: SceneParams) = visual.setSceneParams(params)

    fun resetSceneParams() = visual.resetSceneParams()

    fun nudgeTransform(
        zoomFactor: Float,
        rotationDegrees: Float,
    ) = visual.nudgeTransform(zoomFactor, rotationDegrees)

    fun reportShaderError(error: String?) = visual.reportShaderError(error)

    fun setTransitionStyle(style: TransitionStyle) = visual.setTransitionStyle(style)

    fun setTransitionId(id: String) = visual.setTransitionId(id)

    fun setTransitionDuration(seconds: Float) = visual.setTransitionDuration(seconds)

    fun applyPreset(preset: Preset) = visual.applyPreset(preset)

    fun savePreset(
        name: String,
        folder: String = "",
    ) = presetLibrary.savePreset(name, folder)

    internal fun milkPresetPathFor(preset: Preset): String? = presetLibrary.milkPresetPathFor(preset)

    val activeMilkPath: StateFlow<String?> get() = vizStateStore.activeMilkPath

    fun noteMilkPreset(path: String) = vizStateStore.noteMilkPreset(path)

    fun presetShareLink(name: PresetId): String? = presetLibrary.presetShareLink(name)

    fun importPresetLink(text: String): PresetId? = presetLibrary.importPresetLink(text)

    fun importPresetFile(
        uri: Uri,
        onResult: (PresetId?) -> Unit,
    ) = presetLibrary.importPresetFile(uri, onResult)

    fun presetFile(name: PresetId): File? = presetLibrary.presetFile(name)

    fun deletePreset(name: PresetId) = presetLibrary.deletePreset(name)

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
                override val sceneParams get() = _vizState.value.params

                override fun lfoConfigs() = modulation.lfos.value

                override fun adsrConfigs() = modulation.adsrs.value

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

    fun currentSceneId(): String = _vizState.value.sceneId.value

    fun currentSceneParams(): SceneParams = _vizState.value.params

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

    val visualizerRepository: VisualizerRepository = SessionVisualizerRepository(this)

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
        vizStateStore.flushIfDirty()
        val pendingStoreWrites =
            storeScope.coroutineContext.job.children
                .toList()

        captureController.shutdown()
        engineFailureNotices.close()
        AudioBus.removeConsumer()
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
        AudioBus.addConsumer()
        // Last init block in the class, so every field above is assigned by now — which is what
        // the capture controller needs before it may call back into this session.
        // The mic is turned back on once Settings says whether it was on.
        scope.launch {
            oldSettings.loaded()
            captureController.start()
        }
        musicLibrary.refreshNumericTitles()
        presetLibrary.refreshInitial()
        musicLibrary.refresh()
        textureController.refresh()
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
