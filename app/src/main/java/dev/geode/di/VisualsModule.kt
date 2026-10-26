package dev.geode.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.geode.data.MilkCatalog
import dev.geode.engine.bridge.NativeSnapshots
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.customization.core.BuiltInStyles
import dev.geode.feature.customization.handlers.CustomizationHandlers
import dev.geode.feature.customization.handlers.EnginePublisher
import dev.geode.feature.customization.handlers.SharedPreferencesLegacyModulation
import dev.geode.feature.customization.handlers.StyleCues
import dev.geode.feature.customization.handlers.customizationStore
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.StartLook
import dev.geode.feature.visuals.api.MilkdropPreset
import dev.geode.feature.visuals.api.StyleMark
import dev.geode.feature.visuals.api.Visuals
import dev.geode.feature.visuals.handlers.ContentPickedFiles
import dev.geode.feature.visuals.handlers.ContentPickedPacks
import dev.geode.feature.visuals.handlers.DataStoreVisualsStorage
import dev.geode.feature.visuals.handlers.visualsDataStore
import dev.geode.feature.visuals.handlers.visualsStore
import dev.geode.geodeContainer
import dev.geode.kernel.FeatureRuntime
import dev.geode.render.scene.SceneIds
import dev.geode.visuals.AppPresetFiles
import dev.geode.visuals.EngineSnapshots
import dev.geode.visuals.InstalledMilkdropFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Singleton
import dev.geode.feature.customization.api.StyleMark as SwitchingMark

/**
 * Customization's and Visuals' app-scoped stores (ADR-0003, ADR-0004, #17). Customization reaches the
 * engine through the plan alone: each changed Customization is one snapshot in the [NativeSnapshots]
 * exchange, which the Visual and the analysis read at the start of their next frame. Visuals sits over
 * Customization, listing the Styles, the presets and the MilkDrop folder.
 */
@Module
@InstallIn(SingletonComponent::class)
object VisualsModule {
    /** The one snapshot exchange; it lives as long as the process, as the engine does. */
    @Provides
    @Singleton
    fun provideSnapshots(): NativeSnapshots = NativeSnapshots()

    /** The live Visual's word that Style switching is due, which Customization's handlers follow (#282). */
    @Provides
    @Singleton
    fun provideStyleCues(): StyleCues = StyleCues()

    @Provides
    @Singleton
    @Suppress("InjectDispatcher")
    fun provideCustomization(
        application: Application,
        settings: Settings,
        snapshots: NativeSnapshots,
        styleCues: StyleCues,
        runtime: FeatureRuntime,
    ): CustomizationFeature {
        val engine =
            EnginePublisher(
                snapshotOf = { customization, held ->
                    EngineSnapshots.of(customization, (settings.state.value as? SettingsState.Ready)?.values, held)
                },
                offer = snapshots::offer,
            )
        val handlers = CustomizationHandlers.onDevice(application, settings, engine, SceneIds.DEFAULT, styleCues)
        return customizationStore(handlers, SceneIds.DEFAULT, runtime).also { customization ->
            // What the snapshot takes from Settings reaches the engine as soon as it changes, not only
            // with the next change to the Customization.
            application.geodeContainer.appScope.launch(Dispatchers.Default) {
                settings.state
                    .filterIsInstance<SettingsState.Ready>()
                    .map { it.values.behavior.reducedMotion to it.values.analysis }
                    .distinctUntilChanged()
                    .collect {
                        val state = customization.state.value
                        engine.publish(state.current, state.switchingHeld)
                    }
            }
            // The look Geode starts on (#383): once, as soon as the stored settings are read.
            application.geodeContainer.appScope.launch {
                val start =
                    settings.state
                        .filterIsInstance<SettingsState.Ready>()
                        .first()
                        .values.behavior.startLook
                startCommand(start)?.let(customization::send)
            }
        }
    }

    // The library's DataStore reads and writes its file on its own IO scope, as Settings' does.
    @Provides
    @Singleton
    @Suppress("InjectDispatcher")
    fun provideVisuals(
        application: Application,
        customization: @JvmSuppressWildcards CustomizationFeature,
        runtime: FeatureRuntime,
    ): Visuals =
        visualsStore(
            customization = customization,
            storage = DataStoreVisualsStorage(visualsDataStore(application, CoroutineScope(SupervisorJob() + Dispatchers.IO))),
            presets =
                AppPresetFiles(
                    context = application,
                    root = File(application.filesDir, PRESET_FOLDER),
                    legacy = SharedPreferencesLegacyModulation(application),
                    fallbackStyle = SceneIds.DEFAULT,
                ),
            manifests = BuiltInStyles.all,
            milkdrop = InstalledMilkdropFolder(application),
            picked = ContentPickedFiles(application),
            packs = ContentPickedPacks(application),
            builtInMilkdrop =
                MilkCatalog.load(application).presets.map { MilkdropPreset(it.file, it.name, it.author, builtIn = true) },
            runtime = runtime,
        ).also { visuals ->
            // Style switching's Visualizer styles are the Styles Visuals lists, in its order (#282).
            val listed = visuals.state.value.styles
            customization.send(CustomizationCommand.UseVisualizerStyles(listed.map { it.id }))
            // It draws by the person's marks and Favourites, which live in the Visuals library (#308).
            application.geodeContainer.appScope.launch(Dispatchers.Default) {
                visuals.state
                    .map { it.library }
                    .filterNotNull()
                    .map { library -> library.marks.mapValues { (_, mark) -> switchingMark(mark) } to library.favourites }
                    .distinctUntilChanged()
                    .collect { (marks, favourites) -> customization.send(CustomizationCommand.UseStyleLibrary(marks, favourites)) }
            }
        }

    /** A Visuals library mark as Style switching weighs it. */
    private fun switchingMark(mark: StyleMark): SwitchingMark =
        when (mark) {
            StyleMark.LOVE -> SwitchingMark.LOVE
            StyleMark.NORMAL -> SwitchingMark.NORMAL
            StyleMark.NEVER -> SwitchingMark.NEVER
        }

    /** The app's own preset folder, where Customization saves presets until the person picks another. */
    private const val PRESET_FOLDER = "presets"
}

/** What Customization is sent to open on [look] (#383); nothing for the last look, which it keeps. */
internal fun startCommand(look: StartLook): CustomizationCommand? =
    when (look) {
        StartLook.Last -> null
        is StartLook.Style -> CustomizationCommand.ChooseStyle(look.style)
        is StartLook.Preset -> CustomizationCommand.LoadPreset(look.preset)
    }
