package dev.geode.feature.playback.ui

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.Lyrics
import dev.geode.feature.playback.api.LyricsSource
import dev.geode.feature.playback.api.PlaybackFailure
import dev.geode.feature.playback.api.SyncedLine
import dev.geode.feature.playback.api.Track

/** The models the previews, and so the screenshots, draw. */
internal object PreviewModels {
    private val track = Track(TrackId("content://preview/1"), "Night Drive", "Ann Arbor", Millis(215_000))

    // A made-up loudness, the same on every run.
    private val waveform = List(120) { bar -> 0.2f + 0.8f * ((bar * 37) % 23) / 22f }

    private val synced =
        Lyrics.Synced(
            listOf(
                SyncedLine(Millis(12_000), "Headlights on the empty road"),
                SyncedLine(Millis(24_000), "The radio is turned down low"),
                SyncedLine(Millis(41_000), "Every sign says somewhere else"),
                SyncedLine(Millis(58_000), "And I keep driving by myself"),
                SyncedLine(Millis(73_000), "Night drive, night drive"),
                SyncedLine(Millis(88_000), "Out where the city lights run dry"),
            ),
            LyricsSource.LRC_FILE,
        )

    val playing =
        PlayerModel(
            PlayerStatus.PLAYING,
            track,
            Millis(64_000),
            meantToPlay = true,
            canGoPrevious = true,
            canGoNext = true,
            waveform = waveform,
            lyrics = synced,
            lyricLine = synced.lineAt(Millis(64_000)),
        )
    val paused = playing.copy(status = PlayerStatus.PAUSED, meantToPlay = false)
    val buffering = playing.copy(status = PlayerStatus.BUFFERING)
    val loading = playing.copy(status = PlayerStatus.LOADING, position = Millis.ZERO, lyricLine = NO_LINE)
    val ended = playing.copy(status = PlayerStatus.ENDED, position = track.duration, meantToPlay = false, canGoNext = false)
    val error =
        playing.copy(
            status = PlayerStatus.ERROR,
            position = Millis.ZERO,
            meantToPlay = false,
            failure = PlaybackFailure.ENGINE_NOT_RESPONDING,
            lyricLine = NO_LINE,
        )
    val idle = PlayerModel.IDLE

    /** Playing with the A-B loop set, and no waveform yet: the plain line. */
    val looping = playing.copy(waveform = null, loop = LoopSpan(Millis(40_000), Millis(90_000)))

    val plainLyrics =
        playing.copy(
            lyrics = Lyrics.Plain(listOf("Headlights on the empty road", "The radio is turned down low"), LyricsSource.TRACK_TAGS),
            lyricLine = NO_LINE,
        )
    val noLyrics = playing.copy(lyrics = null, lyricLine = NO_LINE)

    val banner = BannerModel("Could not save the playlist", counting = true)
}
