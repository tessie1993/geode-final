package dev.geode.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.studio.api.Studio
import dev.geode.feature.studio.handlers.androidStudioSources
import dev.geode.feature.studio.handlers.studioStore
import dev.geode.kernel.FeatureRuntime
import javax.inject.Singleton

/**
 * Studio's one app-scoped store (ADR-0003, ADR-0004, #17): the Projects, the videos inside them and
 * the default Project, in Studio's own database, following the save folder through Settings. It lives
 * as long as the process, like the other features' stores, so where the user is browsing survives
 * every route.
 */
@Module
@InstallIn(SingletonComponent::class)
object StudioModule {
    @Provides
    @Singleton
    fun provideStudio(
        application: Application,
        settings: Settings,
        runtime: FeatureRuntime,
    ): Studio = studioStore(androidStudioSources(application, settings), runtime)
}
