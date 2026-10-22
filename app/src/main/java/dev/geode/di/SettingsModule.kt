package dev.geode.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.geode.data.GeodePrefsFiles
import dev.geode.data.PlayerPrefsStore
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.handlers.settingsDataStore
import dev.geode.feature.settings.handlers.settingsStore
import dev.geode.kernel.FeatureRuntime
import dev.geode.playback.PlaybackEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

/**
 * Settings' one app-scoped store (ADR-0003, ADR-0004): every setting the app has, in its own DataStore,
 * taken over from the old preferences files once. Everything that reads a setting reads it here (#17).
 */
@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    // DataStore reads and writes its file on the scope it is given, so it gets its own IO scope.
    @Provides
    @Singleton
    @Suppress("InjectDispatcher")
    fun provideSettings(
        application: Application,
        runtime: FeatureRuntime,
    ): Settings =
        settingsStore(settingsDataStore(application, CoroutineScope(SupervisorJob() + Dispatchers.IO)), runtime).also { settings ->
            // Which player the engine opens with. Read once, when the engine starts: a session that
            // starts before the stored settings are read uses the choice the old settings held.
            PlaybackEngine.nativeEngineChoice = { context ->
                (settings.state.value as? SettingsState.Ready)?.values?.playback?.nativeEngine
                    ?: PlayerPrefsStore(GeodePrefsFiles(context).player).load().nativeEngine
            }
        }
}
