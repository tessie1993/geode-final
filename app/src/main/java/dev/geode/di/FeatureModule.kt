package dev.geode.di

import android.app.Application
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.geode.RingLog
import dev.geode.audio.adapter.AudioAdapter
import dev.geode.audio.adapter.NativeAudioAdapter
import dev.geode.feature.kernel.core.CoroutineTimerScheduler
import dev.geode.feature.kernel.core.TimerScheduler
import dev.geode.feature.kernel.core.featureScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import javax.inject.Qualifier
import javax.inject.Singleton

/** The scope every feature store runs its effects and timers in (ADR-0003). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FeatureStoreScope

/**
 * What every feature store is built from. Nothing in the app injects these yet: the features are
 * built alongside the old code and switched over in one PR (ADR-0003).
 */
@Module
@InstallIn(SingletonComponent::class)
object FeatureModule {
    @Provides
    @Singleton
    @FeatureStoreScope
    fun provideFeatureStoreScope(): CoroutineScope =
        featureScope(Dispatchers.Main.immediate) { failure -> RingLog.note(TAG, "feature effect failed", failure) }

    @Provides
    @Singleton
    fun provideTimerScheduler(
        @FeatureStoreScope scope: CoroutineScope,
    ): TimerScheduler = CoroutineTimerScheduler(scope)

    @Provides
    @Singleton
    fun provideAudioAdapter(application: Application): AudioAdapter = NativeAudioAdapter(application)
}

private const val TAG = "FeatureStore"
