package dev.geode.feature.library.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.IndexStatus
import dev.geode.feature.library.api.LibraryPlaylist
import dev.geode.feature.library.api.LibraryState
import dev.geode.feature.library.api.Play
import dev.geode.feature.library.api.PlaylistRef
import dev.geode.feature.library.api.RuleField
import dev.geode.feature.library.api.RuleOp
import dev.geode.feature.library.api.SmartPlaylist
import dev.geode.feature.library.api.SmartRule
import dev.geode.feature.library.api.Song

/** The songs, plays and playlists the Library's core tests share. */
internal object Fixtures {
    val alpha =
        Song(
            id = TrackId("content://a"),
            title = "Alpha",
            artist = "Ann",
            album = "First",
            albumArtist = "",
            genre = "Rock",
            composer = "Cole",
            path = "/music/rock/alpha.mp3",
            format = "mp3",
            duration = Millis(200_000),
            addedAt = Millis(3_000),
        )

    // Lower case, so sorting by name has to ignore case.
    val beta =
        Song(
            id = TrackId("content://b"),
            title = "beta",
            artist = "Bob",
            album = "First",
            albumArtist = "Various",
            genre = "Jazz",
            composer = "",
            path = "/music/jazz/beta.flac",
            format = "flac",
            duration = Millis(180_000),
            addedAt = Millis(1_000),
            artwork = "content://art/b",
        )

    val gamma =
        Song(
            id = TrackId("content://c"),
            title = "Gamma",
            artist = "Ann",
            album = "Second",
            albumArtist = "",
            genre = "Rock",
            composer = "Cole",
            path = "/music/rock/live/gamma.mp3",
            format = "mp3",
            duration = Millis(240_000),
            addedAt = Millis(2_000),
        )

    /** A voice note: a short clip in a folder of recordings. */
    val voice =
        Song(
            id = TrackId("content://v"),
            title = "Voice 001",
            artist = "",
            album = "",
            albumArtist = "",
            genre = "",
            composer = "",
            path = "/recordings/voice001.m4a",
            format = "m4a",
            duration = Millis(12_000),
            addedAt = Millis(4_000),
        )

    val songs = listOf(alpha, beta, gamma, voice)

    /** Beta twice, Alpha once; the most recent first. */
    val plays = listOf(Play(beta.id, Millis(30)), Play(alpha.id, Millis(20)), Play(beta.id, Millis(10)))

    val evening = LibraryPlaylist(PlaylistRef(7), "Evening", trackCount = 2)
    val morning = LibraryPlaylist(PlaylistRef(3), "Morning", trackCount = 5)

    /** A smart playlist of Ann's songs: Alpha and Gamma. */
    val annSongs = SmartPlaylist("Ann songs", listOf(SmartRule(RuleField.ARTIST, RuleOp.IS, "ann")))

    /** A smart playlist of the songs played at least once, or favourites: Alpha and beta. */
    val played =
        SmartPlaylist(
            "played",
            listOf(SmartRule(RuleField.PLAY_COUNT, RuleOp.AT_LEAST, "1"), SmartRule(RuleField.FAVOURITE, RuleOp.IS, "")),
            matchAll = false,
        )

    /** A Library that has finished scanning, at the top of Songs. */
    val ready: LibraryState =
        shown(
            LibraryState(
                index = IndexStatus.Ready,
                songs = songs,
                favourites = setOf(alpha.id),
                plays = plays,
                playlists = listOf(evening, morning),
            ),
        )

    /** [ready] with its smart playlists, by name. */
    val smart: LibraryState = shown(ready.copy(smartPlaylists = listOf(annSongs, played)))

    /** [state] with the page it shows. */
    fun shown(state: LibraryState): LibraryState = state.copy(page = LibraryPages.pageOf(state))
}
