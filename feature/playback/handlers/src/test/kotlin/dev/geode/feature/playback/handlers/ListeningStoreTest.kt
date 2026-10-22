package dev.geode.feature.playback.handlers

import dev.geode.audio.adapter.FakeAudioAdapter
import dev.geode.audio.adapter.PlaybackAnnouncement
import dev.geode.audio.adapter.PlaybackMark
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.Bookmark
import dev.geode.feature.playback.api.BookmarkId
import dev.geode.feature.playback.api.ListeningOptions
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlayedTrack
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.QueueEnd
import dev.geode.feature.playback.api.SleepTimer
import dev.geode.feature.playback.api.Track
import dev.geode.kernel.FakeClock
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The listening tools (#35) end to end over the audio adapter's fake and storage in memory, with the
 * sleep timer on the fake clock.
 */
class ListeningStoreTest {
    private val a = Track(TrackId("content://a"), "Alpha", "Ann", Millis(200_000))
    private val b = Track(TrackId("content://b"), "Beta", "Bob", Millis(180_000))
    private val long = Track(TrackId("content://long"), "Lecture", "Lu", Millis(3_600_000))
    private val audio = FakeAudioAdapter()
    private val storage = FakePlaybackStorage()
    private val clock = FakeClock()
    private val failures = mutableListOf<FeatureFailure>()
    private val clocks = PlaybackClocks(elapsed = { clock.now }, wall = { WALL_START + clock.now })

    private fun store() = playbackStore(audio, storage, clocks, inPlaceRuntime(clock, failures), shuffleSeed = 1)

    private fun opened(track: Track) {
        audio.announce(PlaybackAnnouncement.TrackChanged(track.id, PlaybackMark(Millis.ZERO, clock.now, moving = false)))
        audio.announce(PlaybackAnnouncement.Started(PlaybackMark(Millis.ZERO, clock.now, moving = true)))
    }

    @Test
    fun `the options saved last are applied from the start`() {
        storage.listening = ListeningOptions(crossfade = Millis(4_000), gapless = false, queueEnd = QueueEnd.SIMILAR)

        val playback = store()

        assertEquals(storage.listening, playback.state.value.options)
        assertEquals(listOf("setTransitions(4000ms, gapless=false)"), audio.joins)
    }

    @Test
    fun `with no options saved the defaults apply, and a change is saved`() {
        val playback = store()

        playback.send(PlaybackCommand.SetCrossfade(Millis(2_000)))

        assertEquals(listOf("setTransitions(0ms, gapless=true)", "setTransitions(2000ms, gapless=true)"), audio.joins)
        assertEquals(ListeningOptions(crossfade = Millis(2_000)), storage.listening)
    }

    @Test
    fun `gapless keeps the next track ready, and the player moving on to it counts as a play`() {
        val playback = store()
        playback.send(PlaybackCommand.Play(listOf(a, b)))
        opened(a)

        assertEquals("prepareNext(content://b)", audio.joins.last())

        audio.announce(PlaybackAnnouncement.TrackChanged(b.id, PlaybackMark(Millis.ZERO, clock.now, moving = true)))

        assertEquals(
            PlayerState.Playing(Queue(listOf(a, b), 1), PlayMark(Millis.ZERO, clock.now, moving = true)),
            playback.state.value.player,
        )
        assertEquals(listOf(PlayedTrack(b, WALL_START), PlayedTrack(a, WALL_START)), playback.state.value.history)
        assertEquals("prepareNext(null)", audio.joins.last())
    }

    @Test
    fun `the sleep timer pauses the player when it runs out`() {
        val playback = store()
        playback.send(PlaybackCommand.Play(listOf(a)))
        opened(a)

        playback.send(PlaybackCommand.StartSleepTimer(Millis(120_000)))
        clock.advanceBy(Millis(119_999))
        assertEquals("setPlaying(true)", audio.calls.last())

        clock.advanceBy(Millis(1))

        assertEquals("setPlaying(false)", audio.calls.last())
        assertEquals(SleepTimer.Off, playback.state.value.sleep)
    }

    @Test
    fun `a bookmark is kept where the clock says the track is`() {
        val playback = store()
        playback.send(PlaybackCommand.Play(listOf(a)))
        opened(a)
        clock.advanceBy(Millis(12_000))

        playback.send(PlaybackCommand.AddBookmark)

        val kept = Bookmark(BookmarkId(1), a, Millis(12_000))
        assertEquals(listOf(kept), playback.state.value.bookmarks)

        playback.send(PlaybackCommand.RemoveBookmark(kept.id))
        assertEquals(emptyList<Bookmark>(), playback.state.value.bookmarks)
    }

    @Test
    fun `a long track goes on from where it was left off, and pausing keeps where it is`() {
        storage.points.value = mapOf(long.id to Millis(1_500_000))
        val playback = store()

        playback.send(PlaybackCommand.Play(listOf(long)))
        opened(long)
        assertEquals("seekTo(1500000ms)", audio.calls[2])

        clock.advanceBy(Millis(60_000))
        playback.send(PlaybackCommand.Pause)

        assertEquals(mapOf(long.id to Millis(60_000)), playback.state.value.resumePoints)
    }

    @Test
    fun `a long track heard to its end starts from the top next time`() {
        storage.points.value = mapOf(long.id to Millis(1_500_000))
        val playback = store()
        playback.send(PlaybackCommand.Play(listOf(long, a)))
        opened(long)

        audio.announce(PlaybackAnnouncement.Ended)

        assertEquals(emptyMap<TrackId, Millis>(), storage.points.value)
        assertEquals(emptyMap<TrackId, Millis>(), playback.state.value.resumePoints)
    }

    @Test
    fun `at the end of the queue, similar songs keep it going when that is the setting`() {
        val similar = Track(TrackId("content://s"), "Same", "Ann", Millis(150_000))
        storage.known = listOf(b, a, similar)
        storage.listening = ListeningOptions(queueEnd = QueueEnd.SIMILAR, gapless = false)
        val playback = store()
        playback.send(PlaybackCommand.Play(listOf(a)))
        opened(a)

        audio.announce(PlaybackAnnouncement.Ended)

        assertEquals("load(content://s, 0ms)", audio.calls[audio.calls.size - 2])
        assertEquals(Queue(listOf(a, similar, b), 1), (playback.state.value.player as PlayerState.Loading).queue)
    }

    @Test
    fun `failing to save a tool is reported by name and playback goes on`() {
        val playback = store()
        playback.send(PlaybackCommand.Play(listOf(a)))
        opened(a)
        storage.failWrites = true

        playback.send(PlaybackCommand.SetShuffle(true))
        playback.send(PlaybackCommand.AddBookmark)

        assertEquals(
            listOf(
                "playback: Could not save the listening settings",
                "playback: Could not save where playback was left off",
                "playback: Could not save the bookmark",
            ),
            failures.map { "${it.feature}: ${it.message}" },
        )
        assertEquals(PlayerState.Playing::class, playback.state.value.player::class)
    }

    private companion object {
        val WALL_START = Millis(1_700_000_000_000)
    }
}
