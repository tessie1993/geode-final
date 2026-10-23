package dev.geode.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.geode.feature.customization.api.CustomizationFeature
import dev.geode.feature.customization.core.BuiltInStyles
import dev.geode.feature.customization.handlers.AndroidPresetPlaces
import dev.geode.feature.customization.handlers.CustomizationHandlers
import dev.geode.feature.customization.handlers.EnginePublisher
import dev.geode.feature.customization.handlers.PresetLibrary
import dev.geode.feature.customization.handlers.SharedPreferencesLegacyModulation
import dev.geode.feature.customization.handlers.customizationStore
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.kernel.FeatureRuntime
import dev.geode.render.scene.SceneIds
import dev.geode.visuals.VisualSnapshots
import javax.inject.Singleton

/**
 * Customization's one app-scoped store (ADR-0003, ADR-0004): the person's Customization of the Style
 * the Visual shows, with its undo history and the preset files. Each Customization it settles on
 * reaches every live Visual as one snapshot carrying its plan (ADR-0002, decision #194).
 */
@Module
@InstallIn(SingletonComponent::class)
// The stores' parameters without Java wildcards, so they match the provided stores.
@JvmSuppressWildcards
object CustomizationModule {
    @Provides
    @Singleton
    fun provideVisualSnapshots(): VisualSnapshots = VisualSnapshots()

    @Provides
    @Singleton
    fun providePresetLibrary(
        application: Application,
        settings: Settings,
    ): PresetLibrary =
        PresetLibrary(
            places = AndroidPresetPlaces(application, settings),
            legacy = SharedPreferencesLegacyModulation(application),
            styles = BuiltInStyles.catalogue,
            fallbackStyle = SceneIds.DEFAULT,
        )

    @Provides
    @Singleton
    fun provideCustomization(
        presets: PresetLibrary,
        settings: Settings,
        snapshots: VisualSnapshots,
        runtime: FeatureRuntime,
    ): CustomizationFeature {
        val engine =
            EnginePublisher(
                snapshotOf = { VisualSnapshots.snapshotOf(it, reducedMotion = settings.reducedMotion()) },
                offer = snapshots::offer,
            )
        return customizationStore(CustomizationHandlers(engine, presets), SceneIds.DEFAULT, runtime)
    }

    private fun Settings.reducedMotion(): Boolean = (state.value as? SettingsState.Ready)?.values?.behavior?.reducedMotion ?: false
}
