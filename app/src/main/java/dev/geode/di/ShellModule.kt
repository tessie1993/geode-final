package dev.geode.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.geode.kernel.FeatureRuntime
import dev.geode.ui.shell.AppUi
import dev.geode.ui.shell.appUiStore
import javax.inject.Singleton

/**
 * The App UI's one store (#182): where the person is, what covers it, and the failure on the banner.
 * It lives as long as the process, like the features' stores, so a new Activity finds it as it was.
 */
@Module
@InstallIn(SingletonComponent::class)
object ShellModule {
    @Provides
    @Singleton
    fun provideAppUi(runtime: FeatureRuntime): AppUi = appUiStore(runtime)
}
