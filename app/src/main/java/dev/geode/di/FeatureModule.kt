package dev.geode.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.geode.RingLog
import dev.geode.audio.EngineTuning
import dev.geode.audio.adapter.AudioAdapter
import dev.geode.audio.adapter.NativeAudioAdapter
import dev.geode.engine.types.Millis
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.geodeContainer
import dev.geode.kernel.ErrorChannel
import dev.geode.kernel.ExecutorTimerScheduler
import dev.geode.kernel.FeatureRuntime
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.Executors
import javax.inject.Singleton

/** What every feature store is built from (ADR-0004, ADR-0005). */
@Module
@InstallIn(SingletonComponent::class)
object FeatureModule {
    /** The app-wide error channel; every report is also written to the local log. */
    @Provides
    @Singleton
    fun provideErrorChannel(): ErrorChannel =
        ErrorChannel { failure -> RingLog.note(TAG, "${failure.feature}: ${failure.message}", failure.cause) }

    /**
     * Reducers on the main thread, handlers on a background pool, timers on their own thread. This is
     * where the stores' dispatchers are injected from, hence the one place they are named.
     */
    @Provides
    @Singleton
    @Suppress("InjectDispatcher")
    fun provideFeatureRuntime(errors: ErrorChannel): FeatureRuntime =
        FeatureRuntime(
            main = Dispatchers.Main.immediate,
            background = Dispatchers.Default,
            timers = ExecutorTimerScheduler(Executors.newSingleThreadScheduledExecutor(::timerThread)),
            errors = errors,
        )

    @Provides
    @Singleton
    fun provideAudioAdapter(
        application: Application,
        settings: Settings,
    ): AudioAdapter =
        NativeAudioAdapter(
            application,
            // The play/pause fade is a Settings choice (#17).
            fadeLength = { (settings.state.value as? SettingsState.Ready)?.values?.playback?.fade ?: Millis.ZERO },
            // Settings tune the engine from the moment it opens, for as long as it runs.
            onOpened = { session -> EngineTuning(settings, session, application.geodeContainer.appScope).start() },
        )
}

private const val TAG = "FeatureStore"
private const val TIMER_THREAD = "geode-feature-timers"

private fun timerThread(task: Runnable): Thread = Thread(task, TIMER_THREAD).apply { isDaemon = true }
