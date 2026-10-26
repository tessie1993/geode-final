package dev.geode.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.geode.export.ExportService
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.customization.core.BuiltInStyles
import dev.geode.feature.customization.handlers.AndroidPresetPlaces
import dev.geode.feature.customization.handlers.PresetLibrary
import dev.geode.feature.customization.handlers.SharedPreferencesLegacyModulation
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.studio.api.Studio
import dev.geode.feature.studio.handlers.androidStudioSources
import dev.geode.feature.studio.handlers.studioStore
import dev.geode.geodeContainer
import dev.geode.kernel.FeatureRuntime
import dev.geode.playback.PlaybackEngine
import dev.geode.render.scene.SceneIds
import dev.geode.studio.AppRenderLooks
import dev.geode.studio.LiveVisualRecorder
import dev.geode.studio.NativeRenderEngine
import dev.geode.ui.LiveVisualPresence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Singleton

/**
 * Studio's one app-scoped store (ADR-0003, ADR-0004, #17): the Projects, the videos inside them, the
 * default Project and the render queue, in Studio's own database, following the save folder and the
 * Renders' settings through Settings, and the Recordings of the live Visual (#317). It lives as long
 * as the process, like the other features' stores, so where the user is browsing and the queue survive
 * every route. A Render's look is frozen
 * from Customization's interface as it is queued (#338).
 */
@Module
@InstallIn(SingletonComponent::class)
// Feature's Command and Fact are in/out, so Kotlin gives the store parameters Java wildcards the
// provided stores lack; without this Dagger finds no binding for them (as in ExportModule).
@JvmSuppressWildcards
object StudioModule {
    @Provides
    @Singleton
    // Preset files are read on IO, as Customization's handlers read them; Renders analyse on Default.
    @Suppress("InjectDispatcher")
    fun provideStudio(
        application: Application,
        settings: Settings,
        customization: CustomizationFeature,
        runtime: FeatureRuntime,
    ): Studio {
        val presets =
            PresetLibrary(
                places = AndroidPresetPlaces(application, settings),
                legacy = SharedPreferencesLegacyModulation(application),
                styles = BuiltInStyles.catalogue,
                fallbackStyle = SceneIds.DEFAULT,
            )
        val looks = AppRenderLooks(customization, presets, BuiltInStyles.catalogue, Dispatchers.IO)
        val engine = NativeRenderEngine(application, settings, LiveVisualPresence::isShown, Dispatchers.Default)
        // A Recording draws from the live Visual and hears the engine's one audio feed (#317).
        val recorder =
            LiveVisualRecorder(application, settings, { PlaybackEngine.feed }, LiveVisualPresence::isShown, Dispatchers.Default)
        return studioStore(androidStudioSources(application, settings, looks, engine, recorder), runtime).also { studio ->
            // The queue runs in the foreground service whenever a Render waits or runs (ADR-0004).
            application.geodeContainer.appScope.launch {
                studio.state
                    .map { state -> state.renders.any { it.active } }
                    .distinctUntilChanged()
                    .collect { busy -> if (busy) ExportService.start(application) }
            }
        }
    }
}
