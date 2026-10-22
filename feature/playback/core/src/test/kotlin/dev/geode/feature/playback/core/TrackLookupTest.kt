package dev.geode.feature.playback.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.LoadReason
import dev.geode.feature.playback.api.Lyrics
import dev.geode.feature.playback.api.LyricsSource
import dev.geode.feature.playback.api.PlayMark
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.playback.api.Queue
import dev.geode.feature.playback.api.SyncedLine
import dev.geode.feature.playback.api.Track
import dev.geode.feature.playback.api.TrackLookup.Found
import dev.geode.feature.playback.api.TrackLookup.Looking
import dev.geode.feature.playback.api.TrackLookup.Missing
import dev.geode.feature.playback.api.Waveform
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The current track's lyrics and seek waveform (#147), as the store's reducer looks them up: again
 * whenever the current track changes, keeping only what comes back for the track still current.
 */
class TrackLookupTest {
    private val a = Track(TrackId("content://a"), "Alpha", "Ann", Millis(200_000))
    private val b = Track(TrackId("content://b"), "Beta", "Bob", Millis(180_000))
    private val start = PlayMark.still(Millis.ZERO)
    private val mark = PlayMark(Millis(30_000), Millis(1_000_000), moving = true)
    private val playingA = PlayerState.Playing(Queue(listOf(a, b), 0), mark)
    private val playingB = PlayerState.Playing(Queue(listOf(a, b), 1), mark)

    private val lyrics = Lyrics.Synced(listOf(SyncedLine(Millis(1_000), "la")), LyricsSource.LRC_FILE)
    private val waveform = Waveform(listOf(0.5f, 1f))

    private val findA = listOf(Perform(PlaybackWork.FindLyrics(a.id)), Perform(PlaybackWork.FindWaveform(a.id)))
    private val findB = listOf(Perform(PlaybackWork.FindLyrics(b.id)), Perform(PlaybackWork.FindWaveform(b.id)))

    @Test
    fun `a track that becomes current is looked up, after what the state machine decided`() {
        val machine = PlaybackReducer.reduce(PlaybackState(), play(a, b))

        val next = PlaybackStoreReducer.reduce(PlaybackState(), play(a, b))

        assertEquals(machine.state.copy(lyrics = Looking(a.id), waveform = Looking(a.id)), next.state)
        assertEquals(machine.effects + findA, next.effects)
    }

    @Test
    fun `moving on to another track looks that one up instead`() {
        val before = PlaybackState(playingA, lyrics = Found(a.id, lyrics), waveform = Found(a.id, waveform))

        val next = PlaybackStoreReducer.reduce(before, PlaybackEvent.Command(PlaybackCommand.Next))

        assertEquals(Looking(b.id), next.state.lyrics)
        assertEquals(Looking(b.id), next.state.waveform)
        assertEquals(findB, next.effects.takeLast(2))
    }

    @Test
    fun `the same track stays looked up, whatever else happens`() {
        val found = PlaybackState(playingA, lyrics = Found(a.id, lyrics), waveform = Missing(a.id))
        val looking = PlaybackState(playingA, lyrics = Looking(a.id), waveform = Looking(a.id))

        for (before in listOf(found, looking)) {
            val next = PlaybackStoreReducer.reduce(before, PlaybackEvent.Command(PlaybackCommand.Pause))

            assertEquals(PlaybackReducer.reduce(before, PlaybackEvent.Command(PlaybackCommand.Pause)), next)
        }
    }

    @Test
    fun `each lookup follows the current track on its own`() {
        val before = PlaybackState(playingB, lyrics = Found(b.id, lyrics), waveform = Looking(a.id))

        val next = PlaybackStoreReducer.reduce(before, PlaybackEvent.PlaylistsChanged(emptyList()))

        assertEquals(Next<PlaybackState, PlaybackEffect>(before.copy(waveform = Looking(b.id)), findB.drop(1)), next)
    }

    @Test
    fun `with nothing playing there is nothing to look up`() {
        val before = PlaybackState(PlayerState.Idle, lyrics = Found(a.id, lyrics), waveform = Missing(a.id))

        val next = PlaybackStoreReducer.reduce(before, PlaybackEvent.HistoryChanged(emptyList()))

        assertEquals(Next<PlaybackState, PlaybackEffect>(PlaybackState(PlayerState.Idle)), next)
    }

    @Test
    fun `a track that is opening or has ended is still the current one`() {
        val loading = PlayerState.Loading(Queue(listOf(a), 0), start, playWhenReady = true, LoadReason.RESTORE)
        val ended = PlayerState.Ended(Queue(listOf(a, b), 1))

        assertEquals(findA, PlaybackStoreReducer.reduce(PlaybackState(loading), PlaybackEvent.LoadTimedOut).effects.takeLast(2))
        assertEquals(findB, PlaybackStoreReducer.reduce(PlaybackState(ended), PlaybackEvent.LoadTimedOut).effects)
    }

    @Test
    fun `what comes back for the current track is kept, found or not`() {
        val looking = PlaybackState(playingA, lyrics = Looking(a.id), waveform = Looking(a.id))

        assertEquals(Found(a.id, lyrics), reduce(looking, PlaybackEvent.LyricsFound(a.id, lyrics)).lyrics)
        assertEquals(Missing(a.id), reduce(looking, PlaybackEvent.LyricsFound(a.id, null)).lyrics)
        assertEquals(Found(a.id, waveform), reduce(looking, PlaybackEvent.WaveformFound(a.id, waveform)).waveform)
        assertEquals(Missing(a.id), reduce(looking, PlaybackEvent.WaveformFound(a.id, null)).waveform)
        assertEquals(Looking(a.id), reduce(looking, PlaybackEvent.LyricsFound(a.id, lyrics)).waveform)
        assertEquals(Looking(a.id), reduce(looking, PlaybackEvent.WaveformFound(a.id, waveform)).lyrics)
    }

    @Test
    fun `what comes back late, for another track or a second time, is dropped`() {
        val befores =
            listOf(
                // Looking up B, the current track, when A's answer comes back.
                PlaybackState(playingB, lyrics = Looking(b.id), waveform = Looking(b.id)),
                // A's answers, again.
                PlaybackState(playingA, lyrics = Missing(a.id), waveform = Missing(a.id)),
                PlaybackState(playingA, lyrics = Found(a.id, lyrics), waveform = Found(a.id, waveform)),
            )

        for (before in befores) {
            assertEquals("${before.lyrics}", before, reduce(before, PlaybackEvent.LyricsFound(a.id, lyrics)))
            assertEquals("${before.lyrics}", before, reduce(before, PlaybackEvent.LyricsFound(a.id, null)))
            assertEquals("${before.waveform}", before, reduce(before, PlaybackEvent.WaveformFound(a.id, waveform)))
            assertEquals("${before.waveform}", before, reduce(before, PlaybackEvent.WaveformFound(a.id, null)))
        }
    }

    @Test
    fun `results carry no effects of their own`() {
        val looking = PlaybackState(playingA, lyrics = Looking(a.id), waveform = Looking(a.id))

        assertEquals(emptyList<PlaybackEffect>(), PlaybackStoreReducer.reduce(looking, PlaybackEvent.LyricsFound(a.id, lyrics)).effects)
        assertEquals(emptyList<PlaybackEffect>(), PlaybackStoreReducer.reduce(looking, PlaybackEvent.WaveformFound(a.id, null)).effects)
    }

    @Test
    fun `the lookups carry the track to look up`() {
        assertEquals(a.id, PlaybackWork.FindLyrics(a.id).track)
        assertEquals(b.id, PlaybackWork.FindWaveform(b.id).track)
        assertEquals(lyrics, PlaybackEvent.LyricsFound(a.id, lyrics).lyrics)
        assertEquals(waveform, PlaybackEvent.WaveformFound(a.id, waveform).waveform)
    }

    private fun reduce(
        state: PlaybackState,
        event: PlaybackEvent,
    ): PlaybackState = PlaybackStoreReducer.reduce(state, event).state

    private fun play(vararg tracks: Track) = PlaybackEvent.Command(PlaybackCommand.Play(tracks.toList()))
}
