package dev.geode.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.customization.core.BuiltInStyles
import dev.geode.feature.customization.handlers.AndroidPresetPlaces
import dev.geode.feature.customization.handlers.PresetLibrary
import dev.geode.feature.customization.handlers.SharedPreferencesLegacyModulation
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.studio.api.RenderProblem
import dev.geode.feature.studio.api.Studio
import dev.geode.feature.studio.core.RenderEnd
import dev.geode.feature.studio.handlers.RenderEngine
import dev.geode.feature.studio.handlers.androidStudioSources
import dev.geode.feature.studio.handlers.studioStore
import dev.geode.kernel.FeatureRuntime
import dev.geode.render.scene.SceneIds
import dev.geode.studio.AppRenderLooks
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

/**
 * Studio's one app-scoped store (ADR-0003, ADR-0004, #17): the Projects, the videos inside them, the
 * default Project and the render queue, in Studio's own database, following the save folder and the
 * Renders' settings through Settings. It lives as long as the process, like the other features'
 * stores, so where the user is browsing and the queue survive every route. A Render's look is frozen
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
    @Suppress("InjectDispatcher") // Preset files are read on IO, as Customization's handlers read them.
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
        // The engine adapter lands with the Render of a Track (#316); until then a Render fails plainly.
        val engine = RenderEngine { _, _, _, _ -> RenderEnd.Failed(RenderProblem.ENGINE_FAILED) }
        return studioStore(androidStudioSources(application, settings, looks, engine), runtime)
    }
}
