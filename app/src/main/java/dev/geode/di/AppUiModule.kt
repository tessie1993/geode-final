package dev.geode.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.geode.appui.AppUi
import dev.geode.appui.appUiStore
import dev.geode.kernel.FeatureRuntime
import javax.inject.Singleton

/**
 * The App UI's one app-scoped store (#182, ADR-0006): navigation, sheets, full-screen visual mode, the
 * app-wide banner and front-end events. Nothing injects it until the switch-over (#17) wires the new
 * shell to it; until then the old shell keeps its own state.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppUiModule {
    @Provides
    @Singleton
    fun provideAppUi(runtime: FeatureRuntime): AppUi = appUiStore(runtime)
}
