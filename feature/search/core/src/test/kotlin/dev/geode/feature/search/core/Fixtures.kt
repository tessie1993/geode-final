package dev.geode.feature.search.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.TrackId
import dev.geode.feature.library.api.Song
import dev.geode.feature.search.api.PlaylistRef
import dev.geode.feature.search.api.PlaylistResult
import dev.geode.feature.search.api.PresetResult
import dev.geode.feature.search.api.SearchCatalogue
import dev.geode.feature.search.api.SearchCommand
import dev.geode.feature.search.api.SearchState
import dev.geode.feature.search.api.SongResult

object Fixtures {
    fun song(
        id: String,
        title: String,
        artist: String = "",
        album: String = "",
        genre: String = "",
        path: String = "/music/$id.mp3",
    ): Song =
        Song(
            id = TrackId(id),
            title = title,
            artist = artist,
            album = album,
            albumArtist = "",
            genre = genre,
            composer = "",
            path = path,
            format = "mp3",
            duration = Millis(200_000),
            addedAt = Millis(1),
        )

    fun Song.result(): SongResult = SongResult(id, title, artist, album, genre, folder)

    val alpha = song("a", "Alpha", artist = "Ann", album = "First", genre = "Rock")
    val beta = song("b", "beta blues", artist = "Bob", album = "Second", genre = "Blues", path = "/music/blues/b.mp3")
    val gamma = song("c", "Gamma", artist = "Ann", album = "Third", genre = "Jazz", path = "/music/jazz/c.flac")

    val road = PlaylistResult(PlaylistRef(1), "Road trip", 12)
    val calm = PlaylistResult(PlaylistRef(2), "calm evenings", 4)

    val night = PresetResult(PresetId("Night drive"), "Night drive")
    val bloom = PresetResult(PresetId("Slow bloom"), "Slow bloom")

    /** Every song by title, the playlists by name, the presets as listed. */
    val catalogue =
        SearchCatalogue(
            songs = listOf(alpha.result(), beta.result(), gamma.result()),
            playlists = listOf(calm, road),
            presets = listOf(night, bloom),
        )

    fun command(command: SearchCommand) = SearchEvent.Command(command)

    fun reduce(
        state: SearchState,
        event: SearchEvent,
    ) = SearchReducer.reduce(state, event)
}
