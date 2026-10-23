package dev.geode.di

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.LoadReason
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackFact
import dev.geode.feature.playback.api.PlaybackFailure
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.Track
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Capture's MusicControl port over Playback (ADR-0005): is a song playing, and pausing it. */
class PlaybackMusicControlTest {
    private val track = Track(TrackId("t1"), "Title", "Artist", Millis(60_000))
    private val queue = Queue(listOf(track), 0)
    private val mark = PlayMark.still(Millis.ZERO)

    @Test
    fun `isPlaying is true only while Playback means to play`() =
        runBlocking {
            assertFalse(control(PlayerState.Idle).isPlaying())
            assertFalse(control(PlayerState.Paused(queue, mark)).isPlaying())
            assertFalse(control(PlayerState.Ended(queue)).isPlaying())
            assertFalse(control(PlayerState.Error(PlaybackFailure.ENGINE_NOT_RESPONDING, queue)).isPlaying())
            assertFalse(control(PlayerState.Loading(queue, mark, playWhenReady = false, LoadReason.PLAY)).isPlaying())
            assertTrue(control(PlayerState.Playing(queue, mark)).isPlaying())
            assertTrue(control(PlayerState.Buffering(queue, mark)).isPlaying())
            assertTrue(control(PlayerState.Loading(queue, mark, playWhenReady = true, LoadReason.PLAY)).isPlaying())
        }

    @Test
    fun `pause sends Pause to Playback`() =
        runBlocking {
            val playback = FakePlayback(PlaybackState(player = PlayerState.Playing(queue, mark)))
            PlaybackMusicControl(playback).pause()
            assertEquals(listOf(PlaybackCommand.Pause), playback.sent)
        }

    private fun control(player: PlayerState): PlaybackMusicControl = PlaybackMusicControl(FakePlayback(PlaybackState(player = player)))

    private class FakePlayback(
        initial: PlaybackState,
    ) : Playback {
        override val state = MutableStateFlow(initial)
        override val events = MutableSharedFlow<PlaybackFact>()
        val sent = mutableListOf<PlaybackCommand>()

        override fun send(command: PlaybackCommand) {
            sent += command
        }
    }
}
