package dev.geode.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.geode.audio.adapter.AudioAdapter
import dev.geode.feature.capture.api.Capture
import dev.geode.feature.capture.core.MusicControl
import dev.geode.feature.capture.handlers.captureStore
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlayerState
import dev.geode.kernel.FeatureRuntime
import javax.inject.Singleton

/**
 * Capture's one app-scoped store (ADR-0003, ADR-0004, #17): the Live input from the microphone, as
 * the audio adapter reports it. It lives as long as the process, so a capture survives every screen.
 */
@Module
@InstallIn(SingletonComponent::class)
object CaptureModule {
    @Provides
    @Singleton
    fun provideCapture(
        audio: AudioAdapter,
        playback: @JvmSuppressWildcards Playback,
        runtime: FeatureRuntime,
    ): Capture = captureStore(audio, PlaybackMusicControl(playback), runtime)
}

/** Capture's [MusicControl] port, adapted to Playback (ADR-0005: features talk only through `:api`). */
internal class PlaybackMusicControl(
    private val playback: Playback,
) : MusicControl {
    override suspend fun isPlaying(): Boolean =
        when (val player = playback.state.value.player) {
            is PlayerState.Loading -> player.playWhenReady
            is PlayerState.Buffering, is PlayerState.Playing -> true
            PlayerState.Idle, is PlayerState.Paused, is PlayerState.Ended, is PlayerState.Error -> false
        }

    override suspend fun pause() = playback.send(PlaybackCommand.Pause)
}
