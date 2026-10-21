package dev.geode.feature.playback.handlers

import dev.geode.audio.adapter.AudioAdapter
import dev.geode.audio.adapter.FakeAudioAdapter
import dev.geode.audio.adapter.PlaybackAnnouncement
import dev.geode.audio.adapter.PlaybackMark
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.LoadReason
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackFact
import dev.geode.feature.playback.api.PlaybackFailure
import dev.geode.feature.playback.api.PlayedTrack
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.PlaylistId
import dev.geode.feature.playback.api.PlaylistSummary
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.Track
import dev.geode.feature.playback.core.BUFFERING_GRACE
import dev.geode.feature.playback.core.LOAD_TIMEOUT
import dev.geode.kernel.FakeClock
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Playback end to end over the audio adapter's fake and storage in memory: Commands in, adapter calls
 * out, announcements back through the reducer, timers on the fake clock, and failures on the
 * app-wide error channel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStoreTest {
    private val a = Track(TrackId("content://a"), "Alpha", "Ann", Millis(200_000))
    private val b = Track(TrackId("content://b"), "Beta", "Bob", Millis(180_000))
    private val audio = FakeAudioAdapter()
    private val storage = FakePlaybackStorage()
    private val clock = FakeClock()
    private val failures = mutableListOf<FeatureFailure>()
    private val clocks = PlaybackClocks(elapsed = { clock.now }, wall = { WALL_START + clock.now })

    private fun store(adapter: AudioAdapter = audio) = playbackStore(adapter, storage, clocks, inPlaceRuntime(clock, failures))

    @Test
    fun `playing loads the track, starts it, and follows what the adapter announces`() {
        val playback = store()

        playback.send(PlaybackCommand.Play(listOf(a, b)))
        assertEquals(listOf("load(content://a, 0ms)", "setPlaying(true)"), audio.calls)

        audio.announce(PlaybackAnnouncement.TrackChanged(a.id, PlaybackMark(Millis.ZERO, Millis.ZERO, moving = false)))
        audio.announce(PlaybackAnnouncement.Started(PlaybackMark(Millis.ZERO, Millis(5), moving = true)))

        assertEquals(
            PlayerState.Playing(Queue(listOf(a, b), 0), PlayMark(Millis.ZERO, Millis(5), moving = true)),
            playback.state.value.player,
        )
        assertEquals(listOf(PlayedTrack(a, WALL_START)), playback.state.value.history)
        assertEquals(SavedSession(Queue(listOf(a, b), 0), Millis.ZERO), storage.session)
        assertEquals(0, clock.pendingCount)
    }

    @Test
    fun `a pause while the track opens wins over the start it announces`() {
        val playback = store()
        playback.send(PlaybackCommand.Play(listOf(a)))

        playback.send(PlaybackCommand.Pause)
        audio.announce(PlaybackAnnouncement.Started(PlaybackMark(Millis.ZERO, Millis.ZERO, moving = true)))
        audio.announce(PlaybackAnnouncement.TrackChanged(a.id, PlaybackMark(Millis.ZERO, Millis.ZERO, moving = false)))

        assertEquals(listOf("load(content://a, 0ms)", "setPlaying(true)", "setPlaying(false)", "setPlaying(false)"), audio.calls)
        assertEquals(PlayerState.Paused(Queue(listOf(a), 0), PlayMark.still(Millis.ZERO)), playback.state.value.player)
    }

    @Test
    fun `a song that cannot be played is skipped silently`() {
        val playback = store()
        playback.send(PlaybackCommand.Play(listOf(a, b)))

        audio.announce(PlaybackAnnouncement.Failed("ERROR_CODE_DECODING_FAILED"))

        assertEquals(listOf("load(content://a, 0ms)", "setPlaying(true)", "load(content://b, 0ms)", "setPlaying(true)"), audio.calls)
        assertEquals(b, (playback.state.value.player as PlayerState.Loading).queue.current)
        assertEquals(emptyList<FeatureFailure>(), failures)
    }

    @Test
    fun `a failure under a playing track reloads it where it was`() {
        val playback = store()
        playback.send(PlaybackCommand.Play(listOf(a, b)))
        audio.announce(PlaybackAnnouncement.TrackChanged(a.id, PlaybackMark(Millis.ZERO, Millis.ZERO, moving = false)))
        audio.announce(PlaybackAnnouncement.Started(PlaybackMark(Millis.ZERO, Millis.ZERO, moving = true)))
        audio.calls.clear()

        clock.advanceBy(Millis(5_000))
        audio.announce(PlaybackAnnouncement.Failed("next track: ERROR_CODE_DECODING_FAILED"))

        assertEquals(listOf("load(content://a, 5000ms)", "setPlaying(true)"), audio.calls)
        assertEquals(LoadReason.RECOVER, (playback.state.value.player as PlayerState.Loading).reason)
    }

    @Test
    fun `buffering that never starts gives way to paused`() {
        val playback = store()
        playback.send(PlaybackCommand.Play(listOf(a)))
        audio.announce(PlaybackAnnouncement.TrackChanged(a.id, PlaybackMark(Millis.ZERO, Millis.ZERO, moving = false)))
        assertTrue(playback.state.value.player is PlayerState.Buffering)

        clock.advanceBy(BUFFERING_GRACE)

        assertEquals(PlayerState.Paused(Queue(listOf(a), 0), PlayMark.still(Millis.ZERO)), playback.state.value.player)
    }

    @Test
    fun `an engine that never opens the track is an error on the banner`() {
        val playback = store()
        playback.send(PlaybackCommand.Play(listOf(a)))

        clock.advanceBy(LOAD_TIMEOUT)

        assertEquals(PlayerState.Error(PlaybackFailure.ENGINE_NOT_RESPONDING, Queue(listOf(a), 0)), playback.state.value.player)
        assertEquals(listOf("playback: The player did not answer in time"), failures.map { "${it.feature}: ${it.message}" })
    }

    @Test
    fun `the app opens where playback was left off, paused`() {
        storage.session = SavedSession(Queue(listOf(a, b), 1), Millis(42_000))

        val playback = store()

        assertEquals(listOf("load(content://b, 42000ms)", "setPlaying(false)"), audio.calls)
        assertEquals(
            PlayerState.Loading(Queue(listOf(a, b), 1), PlayMark.still(Millis(42_000)), playWhenReady = false, LoadReason.RESTORE),
            playback.state.value.player,
        )
    }

    @Test
    fun `pausing saves the position the clock gives`() {
        val playback = store()
        playback.send(PlaybackCommand.Play(listOf(a)))
        audio.announce(PlaybackAnnouncement.TrackChanged(a.id, PlaybackMark(Millis.ZERO, Millis.ZERO, moving = false)))
        audio.announce(PlaybackAnnouncement.Started(PlaybackMark(Millis.ZERO, Millis.ZERO, moving = true)))

        clock.advanceBy(Millis(12_000))
        playback.send(PlaybackCommand.Pause)

        assertEquals(SavedSession(Queue(listOf(a), 0), Millis(12_000)), storage.session)
        assertEquals("setPlaying(false)", audio.calls.last())
    }

    @Test
    fun `seeking asks the adapter`() {
        val playback = store()
        playback.send(PlaybackCommand.Play(listOf(a)))
        audio.announce(PlaybackAnnouncement.TrackChanged(a.id, PlaybackMark(Millis.ZERO, Millis.ZERO, moving = false)))

        playback.send(PlaybackCommand.SeekTo(Millis(90_000)))

        assertEquals("seekTo(90000ms)", audio.calls.last())
    }

    @Test
    fun `the history saved as a playlist is announced and listed`() =
        runTest {
            val facts = mutableListOf<PlaybackFact>()
            val playback = store()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { playback.events.collect { facts += it } }
            storage.recordPlay(b, Millis(2))
            storage.recordPlay(a, Millis(1))

            playback.send(PlaybackCommand.SaveHistoryAsPlaylist("Was"))

            assertEquals(listOf(PlaybackFact.PlaylistSaved("Was")), facts)
            assertEquals(listOf(PlaylistSummary(PlaylistId(1), "Was", trackCount = 2)), playback.state.value.playlists)
            assertEquals(listOf(a, b), storage.tracksOf[PlaylistId(1)])
        }

    @Test
    fun `a playlist plays from its first track and can be deleted`() {
        val playback = store()
        playback.send(PlaybackCommand.CreatePlaylist("Mix", listOf(b, a)))

        playback.send(PlaybackCommand.PlayPlaylist(PlaylistId(1)))
        assertEquals(listOf("load(content://b, 0ms)", "setPlaying(true)"), audio.calls)

        playback.send(PlaybackCommand.DeletePlaylist(PlaylistId(1)))
        assertEquals(emptyList<PlaylistSummary>(), playback.state.value.playlists)
    }

    @Test
    fun `a storage failure is reported by name and playback goes on`() {
        val playback = store()
        storage.failWrites = true

        playback.send(PlaybackCommand.CreatePlaylist("Mix", listOf(a)))
        playback.send(PlaybackCommand.Play(listOf(a)))
        audio.announce(PlaybackAnnouncement.TrackChanged(a.id, PlaybackMark(Millis.ZERO, Millis.ZERO, moving = false)))

        assertEquals(
            listOf(
                "playback: Could not save the playlist",
                "playback: Could not add the song to the history",
                "playback: Could not save where playback was left off",
            ),
            failures.map { "${it.feature}: ${it.message}" },
        )
        assertTrue(playback.state.value.player is PlayerState.Buffering)
    }

    @Test
    fun `an adapter that throws counts as a failed player`() {
        val playback = store(Throwing(audio))

        playback.send(PlaybackCommand.Play(listOf(a, b)))

        // Both loads threw: the first was skipped, then the queue ran out.
        assertEquals(PlayerState.Ended(Queue(listOf(a, b), 1)), playback.state.value.player)
    }

    /** An adapter whose loads throw, as a crashed engine's would. */
    private class Throwing(
        private val audio: FakeAudioAdapter,
    ) : AudioAdapter by audio {
        override suspend fun load(
            track: TrackId,
            from: Millis,
        ) = error("engine gone")
    }

    private companion object {
        val WALL_START = Millis(1_700_000_000_000)
    }
}
