package dev.geode.feature.playback.handlers

import dev.geode.audio.adapter.FakeAudioAdapter
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.BANNER_VISIBLE
import dev.geode.feature.playback.api.Banner
import dev.geode.feature.playback.api.Lyrics
import dev.geode.feature.playback.api.LyricsSource
import dev.geode.feature.playback.api.Notice
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.SyncedLine
import dev.geode.feature.playback.api.Track
import dev.geode.feature.playback.api.TrackLookup
import dev.geode.feature.playback.core.WAVEFORM_BARS
import dev.geode.kernel.FakeClock
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the Player shows beside the music (#147), end to end through the store: lyrics and the seek
 * waveform found for the current track, and the app-wide banner fed by the error channel.
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
    private val loud = mapOf(b.id to FloatArray(WAVEFORM_BARS) { if (it == 0) 0.5f else 0.25f })

    private fun store(
        lyrics: LyricsFiles = FakeLyricsFiles(lrc),
        loudness: TrackLoudness = FakeLoudness(loud),
    ) = playbackStore(audio, FakePlaybackStorage(), clocks, runtime, lyrics = lyrics, loudness = loudness)

    @Test
    fun `the current track's lyrics are read and kept`() {
        val playback = store()

        playback.send(PlaybackCommand.Play(listOf(a, b)))

        val expected =
            Lyrics.Synced(listOf(SyncedLine(Millis(1_000), "First"), SyncedLine(Millis(3_000), "Second")), LyricsSource.LRC_FILE)
        assertEquals(TrackLookup.Found(a.id, expected), playback.state.value.lyrics)
        assertEquals(TrackLookup.Missing(a.id), playback.state.value.waveform)
    }

    @Test
    fun `an lrc file with no words gives way to the track's tags`() {
        val empty = mapOf(a.id to LyricsText("[ar:Ann]\n", LyricsSource.LRC_FILE))
        val playback = store(lyrics = FakeLyricsFiles(empty, also = listOf(LyricsText("Plain words", LyricsSource.TRACK_TAGS))))

        playback.send(PlaybackCommand.Play(listOf(a)))

        assertEquals(TrackLookup.Found(a.id, Lyrics.Plain(listOf("Plain words"), LyricsSource.TRACK_TAGS)), playback.state.value.lyrics)
    }

    @Test
    fun `the current track's loudness becomes its seek waveform`() {
        val playback = store()

        playback.send(PlaybackCommand.Play(listOf(a, b), startAt = 1))

        val waveform = (playback.state.value.waveform as TrackLookup.Found).value
        assertEquals(b.id, playback.state.value.waveform.track)
        assertEquals(WAVEFORM_BARS, waveform.levels.size)
        assertEquals(listOf(1f, 0.5f), waveform.levels.take(2))
        assertEquals(TrackLookup.Missing(b.id), playback.state.value.lyrics)
    }

    @Test
    fun `lyrics or loudness that cannot be read are none, with no banner`() {
        val playback = store(lyrics = FailingLyrics, loudness = FailingLoudness)

        playback.send(PlaybackCommand.Play(listOf(a)))

        assertEquals(TrackLookup.Missing(a.id), playback.state.value.lyrics)
        assertEquals(TrackLookup.Missing(a.id), playback.state.value.waveform)
        assertEquals(emptyList<FeatureFailure>(), failures)
        assertEquals(Banner.Hidden, playback.state.value.banner)
    }

    @Test
    fun `with no sources wired, a track has no lyrics and no waveform`() {
        val playback = playbackStore(audio, FakePlaybackStorage(), clocks, runtime)

        playback.send(PlaybackCommand.Play(listOf(a)))

        assertEquals(TrackLookup.Missing(a.id), playback.state.value.lyrics)
        assertEquals(TrackLookup.Missing(a.id), playback.state.value.waveform)
    }

    @Test
    fun `a failure any feature reports waits for the banner, shows, then goes`() {
        val playback = store()

        runtime.errors.report(FeatureFailure("library", "Could not read the music"))
        assertEquals(Banner.Waiting(Notice("library", "Could not read the music")), playback.state.value.banner)

        playback.send(PlaybackCommand.BannerShown)
        assertEquals(Banner.Showing(Notice("library", "Could not read the music")), playback.state.value.banner)

        clock.advanceBy(BANNER_VISIBLE - Millis(1))
        assertEquals(Banner.Showing(Notice("library", "Could not read the music")), playback.state.value.banner)
        clock.advanceBy(Millis(1))
        assertEquals(Banner.Hidden, playback.state.value.banner)
    }

    @Test
    fun `a newer failure replaces the one shown, and dismissing hides it`() {
        val playback = store()
        runtime.errors.report(FeatureFailure("library", "Could not read the music"))
        playback.send(PlaybackCommand.BannerShown)

        clock.advanceBy(Millis(5_000))
        runtime.errors.report(FeatureFailure("engine", "The audio engine is restarting"))
        clock.advanceBy(Millis(5_000))
        assertEquals(Banner.Showing(Notice("engine", "The audio engine is restarting")), playback.state.value.banner)

        playback.send(PlaybackCommand.DismissBanner)
        assertEquals(Banner.Hidden, playback.state.value.banner)
        assertEquals(0, clock.pendingCount)
    }

    @Test
    fun `playback's own failures reach the banner too`() {
        val playback = store()

        playback.send(PlaybackCommand.Play(listOf(a)))
        // The engine never answers the load.
        clock.advanceBy(Millis(10_000))

        assertEquals(Banner.Waiting(Notice(PLAYBACK, "The player did not answer in time")), playback.state.value.banner)
    }

    private class FakeLyricsFiles(
        private val files: Map<TrackId, LyricsText>,
        private val also: List<LyricsText> = emptyList(),
    ) : LyricsFiles {
        override suspend fun find(track: TrackId): List<LyricsText> = listOfNotNull(files[track]) + also
    }

    private class FakeLoudness(
        private val levels: Map<TrackId, FloatArray>,
    ) : TrackLoudness {
        override suspend fun levels(track: TrackId): FloatArray? = levels[track]
    }

    private object FailingLyrics : LyricsFiles {
        override suspend fun find(track: TrackId): List<LyricsText> = throw IllegalStateException("unreadable")
    }

    private object FailingLoudness : TrackLoudness {
        override suspend fun levels(track: TrackId): FloatArray? = throw IllegalStateException("unreadable")
    }
}
