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
import dev.geode.feature.settings.api.CrossfadeCurve
import dev.geode.feature.settings.api.PlaybackSettings
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.geodeContainer
import dev.geode.kernel.ErrorChannel
import dev.geode.kernel.ExecutorTimerScheduler
import dev.geode.kernel.FeatureRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
            // Which player the engine opens with is a Settings choice (#17): the engine waits for the
            // stored settings rather than opening with a default and keeping it for the whole run.
            nativeEngine = {
                settings.state
                    .filterIsInstance<SettingsState.Ready>()
                    .first()
                    .values.playback.nativeEngine
            },
            // The play/pause fade is a Settings choice (#17).
            fadeLength = { settings.playbackValues()?.fade ?: Millis.ZERO },
            // So is the crossfade's curve. Its length and gapless reach the adapter from Playback.
            crossfadeCurve = { (settings.playbackValues()?.crossfadeCurve ?: CrossfadeCurve.EQUAL_POWER).ordinal },
            // Settings tune the engine from the moment it opens, for as long as it runs.
            onOpened = { session -> EngineTuning(settings, session, application.geodeContainer.appScope).start() },
        ).also { adapter ->
            // A new curve applies from the next crossfade on.
            application.geodeContainer.appScope.launch {
                settings.state
                    .filterIsInstance<SettingsState.Ready>()
                    .map { it.values.playback.crossfadeCurve }
                    .distinctUntilChanged()
                    .collect { adapter.crossfadeCurveChanged() }
            }
        }
}

private const val TAG = "FeatureStore"
private const val TIMER_THREAD = "geode-feature-timers"

private fun timerThread(task: Runnable): Thread = Thread(task, TIMER_THREAD).apply { isDaemon = true }

private fun Settings.playbackValues(): PlaybackSettings? = (state.value as? SettingsState.Ready)?.values?.playback
