package dev.geode.di

import android.app.Application
import android.net.Uri
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.geode.analysis.FeatureTimeline
import dev.geode.data.EditorProjectStore
import dev.geode.data.ExportDefaults
import dev.geode.engine.bridge.CustomizationSnapshot
import dev.geode.export.DriftStop
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.currentQueue
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.geodeContainer
import dev.geode.playback.toUri
import dev.geode.ui.EditorController
import dev.geode.ui.ExportController
import dev.geode.ui.GuiPrefs
import dev.geode.ui.SettingsForOldScreens
import dev.geode.ui.TrackAnalysisController
import dev.geode.visuals.EngineSnapshots
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * The export pipeline the Export sheet and the editor still drive (#17): the whole-song analysis
 * (ADR-0001), the open editor project and the export, studio and loop renders. Not the Studio feature
 * (ADR-0005), whose store lives in [StudioModule]. Each lives as long as the process.
 */
@Module
@InstallIn(SingletonComponent::class)
// Playback's Command and Fact are in/out, so Kotlin gives the store parameter Java wildcards the
// provided store lacks; without this Dagger finds no binding for it (as in LibraryModule).
@JvmSuppressWildcards
object ExportModule {
    /** The song's whole-file analysis for exports and renders, cached per song (ADR-0001). */
    @Provides
    @Singleton
    fun provideTrackAnalysisController(
        application: Application,
        settings: SettingsForOldScreens,
    ): TrackAnalysisController =
        TrackAnalysisController(
            application,
            object : TrackAnalysisController.Host {
                override val guiPrefs: GuiPrefs get() = settings.guiPrefs.value
            },
        )

    /** The open editor project. Edits run on the main thread; the store is written one write at a time. */
    @OptIn(ExperimentalCoroutinesApi::class)
    @Provides
    @Singleton
    fun provideEditorController(application: Application): EditorController =
        EditorController(
            store = EditorProjectStore(application),
            scope = application.geodeContainer.appScope,
            storeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
        ).also { it.open() }

    /**
     * Exports the song Playback has loaded, with Settings' export defaults and the current
     * Customization, frozen as the export starts (#337).
     */
    @Provides
    @Singleton
    fun provideExportController(
        application: Application,
        analysis: TrackAnalysisController,
        settings: SettingsForOldScreens,
        values: Settings,
        customization: CustomizationFeature,
        playback: Playback,
    ): ExportController =
        ExportController(
            application,
            application.geodeContainer.appScope,
            object : ExportController.Host {
                override val exportUri: Uri? get() =
                    playback.state.value.player.currentQueue
                        ?.current
                        ?.id
                        ?.toUri()

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

                override val exportDefaults: ExportDefaults get() = settings.exportDefaults()

                override fun look(drift: DriftStop): CustomizationSnapshot =
                    EngineSnapshots.forRender(
                        drift.applyTo(customization.state.value.current),
                        (values.state.value as? SettingsState.Ready)?.values,
                    )
            },
        )
}
