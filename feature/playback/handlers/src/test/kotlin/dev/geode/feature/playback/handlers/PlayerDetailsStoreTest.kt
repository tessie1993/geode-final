package dev.geode.feature.playback.handlers

import dev.geode.audio.adapter.FakeAudioAdapter
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.Lyrics
import dev.geode.feature.playback.api.LyricsSource
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.SyncedLine
import dev.geode.feature.playback.api.Track
import dev.geode.feature.playback.api.TrackLookup
import dev.geode.kernel.FakeClock
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the Player shows beside the music (#147), end to end through the store: the lyrics found for
 * the current track. Playback keeps no banner: failures are only reported to the error channel.
 */
class PlayerDetailsStoreTest {
    private val a = Track(TrackId("content://a"), "Alpha", "Ann", Millis(200_000))
    private val b = Track(TrackId("content://b"), "Beta", "Bob", Millis(180_000))
    private val audio = FakeAudioAdapter()
    private val clock = FakeClock()
    private val failures = mutableListOf<FeatureFailure>()
    private val runtime = inPlaceRuntime(clock, failures)
    private val clocks = PlaybackClocks(elapsed = { clock.now }, wall = { clock.now })

    private val lrc = mapOf(a.id to LyricsText("[00:01.00]First\n[00:03.00]Second", LyricsSource.LRC_FILE))

    private fun store(lyrics: LyricsFiles = FakeLyricsFiles(lrc)) =
        playbackStore(audio, FakePlaybackStorage(), clocks, runtime, lyrics = lyrics)

    @Test
    fun `the current track's lyrics are read and kept`() {
        val playback = store()

        playback.send(PlaybackCommand.Play(listOf(a, b)))

        val expected =
            Lyrics.Synced(listOf(SyncedLine(Millis(1_000), "First"), SyncedLine(Millis(3_000), "Second")), LyricsSource.LRC_FILE)
        assertEquals(TrackLookup.Found(a.id, expected), playback.state.value.lyrics)
    }

    @Test
    fun `an lrc file with no words gives way to the track's tags`() {
        val empty = mapOf(a.id to LyricsText("[ar:Ann]\n", LyricsSource.LRC_FILE))
        val playback = store(lyrics = FakeLyricsFiles(empty, also = listOf(LyricsText("Plain words", LyricsSource.TRACK_TAGS))))

        playback.send(PlaybackCommand.Play(listOf(a)))

        assertEquals(TrackLookup.Found(a.id, Lyrics.Plain(listOf("Plain words"), LyricsSource.TRACK_TAGS)), playback.state.value.lyrics)
    }

    @Test
    fun `moving on to a track with no lyrics finds none`() {
        val playback = store()

        playback.send(PlaybackCommand.Play(listOf(a, b), startAt = 1))

        assertEquals(TrackLookup.Missing(b.id), playback.state.value.lyrics)
    }

    @Test
    fun `lyrics that cannot be read are none, and no failure is reported`() {
        val playback = store(lyrics = FailingLyrics)

        playback.send(PlaybackCommand.Play(listOf(a)))

        assertEquals(TrackLookup.Missing(a.id), playback.state.value.lyrics)
        assertEquals(emptyList<FeatureFailure>(), failures)
    }

    @Test
    fun `built as the switch-over builds it, with no lyrics source, a track has no lyrics`() {
        val playback = playbackStore(audio, FakePlaybackStorage(), clocks, runtime)

        playback.send(PlaybackCommand.Play(listOf(a)))

        assertEquals(TrackLookup.Missing(a.id), playback.state.value.lyrics)
    }

    private class FakeLyricsFiles(
        private val files: Map<TrackId, LyricsText>,
        private val also: List<LyricsText> = emptyList(),
    ) : LyricsFiles {
        override suspend fun find(track: TrackId): List<LyricsText> = listOfNotNull(files[track]) + also
    }

    private object FailingLyrics : LyricsFiles {
        override suspend fun find(track: TrackId): List<LyricsText> = throw IllegalStateException("unreadable")
    }
}
