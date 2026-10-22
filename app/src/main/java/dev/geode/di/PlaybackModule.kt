package dev.geode.di

import android.app.Application
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.geode.audio.adapter.AudioAdapter
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.handlers.PlaybackClocks
import dev.geode.feature.playback.handlers.PlaybackDatabase
import dev.geode.feature.playback.handlers.PlaybackStorage
import dev.geode.feature.playback.handlers.RoomPlaybackStorage
import dev.geode.feature.playback.handlers.playbackStore
import dev.geode.feature.settings.api.Settings
import dev.geode.geodeContainer
import dev.geode.kernel.FeatureRuntime
import dev.geode.playback.ImportingPlaybackStorage
import dev.geode.playback.PlaybackServiceLink
import dev.geode.playback.TransitionsFromSettings
import javax.inject.Singleton

/**
 * Playback's one app-scoped store (ADR-0003, ADR-0004): the music, the queue, the playlists and the
 * history, for every screen, the media session, the widget and the car. It is built on first use and
 * lives as long as the process, so no screen can restart the music.
 */
@Module
@InstallIn(SingletonComponent::class)
object PlaybackModule {
    @Provides
    @Singleton
    fun providePlaybackStorage(application: Application): PlaybackStorage =
        ImportingPlaybackStorage.of(
            context = application,
            stored = RoomPlaybackStorage(PlaybackDatabase.open(application).dao()),
            marks = application.getSharedPreferences(IMPORT_MARKS, Context.MODE_PRIVATE),
        )

    @Provides
    @Singleton
    fun providePlayback(
        application: Application,
        audio: AudioAdapter,
        storage: PlaybackStorage,
        settings: Settings,
        runtime: FeatureRuntime,
    ): Playback =
        playbackStore(audio, storage, PlaybackClocks.system(), runtime).also { playback ->
            // While Playback means to play, its media session's service stays connected, so the
            // notification shows and the music outlives the screen (#36).
            PlaybackServiceLink(application, playback).start()
            // It joins tracks as Settings say (#17).
            TransitionsFromSettings.follow(settings, playback, application.geodeContainer.appScope)
        }

    private const val IMPORT_MARKS = "geode_playback_import"
}
