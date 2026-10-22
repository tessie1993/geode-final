package dev.geode.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.geode.RingLog
import dev.geode.audio.adapter.AudioAdapter
import dev.geode.audio.adapter.NativeAudioAdapter
import dev.geode.data.PlayerPrefsStore
import dev.geode.engine.types.Millis
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
    fun provideAudioAdapter(application: Application): AudioAdapter =
        NativeAudioAdapter(
            application,
            // The old player settings' play/pause fade, until Settings owns it (#17, Settings slice).
            fadeLength = { Millis(PlayerPrefsStore(application.geodeContainer.prefsFiles.player).load().fadeMs.toLong()) },
        )
}

private const val TAG = "FeatureStore"
private const val TIMER_THREAD = "geode-feature-timers"

private fun timerThread(task: Runnable): Thread = Thread(task, TIMER_THREAD).apply { isDaemon = true }
