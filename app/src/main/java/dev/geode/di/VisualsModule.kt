package dev.geode.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.geode.data.MilkCatalog
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.customization.core.BuiltInStyles
import dev.geode.feature.customization.handlers.PresetLibrary
import dev.geode.feature.visuals.api.MilkdropPreset
import dev.geode.feature.visuals.api.Visuals
import dev.geode.feature.visuals.handlers.ContentPickedFiles
import dev.geode.feature.visuals.handlers.DataStoreVisualsStorage
import dev.geode.feature.visuals.handlers.FileMilkdropFolder
import dev.geode.feature.visuals.handlers.visualsDataStore
import dev.geode.feature.visuals.handlers.visualsStore
import dev.geode.geodeContainer
import dev.geode.kernel.FeatureRuntime
import dev.geode.render.scene.MilkStarterPack
import dev.geode.visuals.LibraryPresetFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Singleton

/**
 * The Visuals feature's one app-scoped store (ADR-0003, ADR-0004): the Styles to choose from, the
 * person's Favourites, recent Styles and folders, the Presets browser and the MilkDrop presets and
 * textures. Choosing a Style or a preset goes through Customization, the same path for every Style,
 * MilkDrop's among them (#189).
 */
@Module
@InstallIn(SingletonComponent::class)
// The stores' parameters without Java wildcards, so they match the provided stores.
@JvmSuppressWildcards
object VisualsModule {
    // DataStore reads and writes its file on the scope it is given, so it gets its own IO scope; the
    // preset files and the MilkDrop catalogue are read on IO too.
    @Provides
    @Singleton
    @Suppress("InjectDispatcher")
    fun provideVisuals(
        application: Application,
        customization: CustomizationFeature,
        presets: PresetLibrary,
        runtime: FeatureRuntime,
    ): Visuals {
        val milkdrop = FileMilkdropFolder.of(application)
        // The curated MilkDrop presets Geode ships (#93) are copied into the MilkDrop folder, once per version.
        application.geodeContainer.appScope.launch(Dispatchers.IO) { MilkStarterPack.install(application) }
        val visuals =
            visualsStore(
                customization = customization,
                storage = DataStoreVisualsStorage(visualsDataStore(application, CoroutineScope(SupervisorJob() + Dispatchers.IO))),
                presets = LibraryPresetFiles(presets, application.contentResolver, Dispatchers.IO),
                manifests = BuiltInStyles.all,
                milkdrop = milkdrop,
                picked = ContentPickedFiles(application),
                builtInMilkdrop =
                    MilkCatalog.load(application).presets.map { MilkdropPreset(it.file, it.name, it.author, builtIn = true) },
                runtime = runtime,
            )
        // Customization switches through the Visualizer styles in the Visuals tab's order.
        customization.send(CustomizationCommand.UseVisualizerStyles(visuals.state.value.styles.map { it.id }))
        return visuals
    }
}
