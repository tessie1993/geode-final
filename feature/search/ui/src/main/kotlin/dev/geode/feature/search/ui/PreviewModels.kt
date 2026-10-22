package dev.geode.feature.search.ui

import dev.geode.engine.types.PresetId
import dev.geode.engine.types.TrackId
import dev.geode.feature.search.api.PlaylistRef
import dev.geode.feature.search.api.PlaylistResult
import dev.geode.feature.search.api.PresetResult
import dev.geode.feature.search.api.SearchResults
import dev.geode.feature.search.api.SongResult

/** The models the previews, and so the screenshots, show. */
internal object PreviewModels {
    private fun song(
        id: String,
        title: String,
        artist: String,
        album: String,
    ) = SongResult(TrackId("content://media/$id"), title, artist, album, "", "/music")

    val empty = SearchModel("", SearchResults.NotSearching)

    val found =
        SearchModel(
            query = "night",
            results =
                SearchResults.Found(
                    query = "night",
                    songs =
                        listOf(
                            song("1", "Night Drive", "Neon Coast", "Afterglow"),
                            song("2", "Nightfall", "The Lanterns", ""),
                            song("3", "Into the Night", "", "Late Hours"),
                        ),
                    playlists =
                        listOf(
                            PlaylistResult(PlaylistRef(1), "Late night", 24),
                            PlaylistResult(PlaylistRef(2), "Night run", 1),
                        ),
                    presets = listOf(PresetResult(PresetId("Night drive"), "Night drive")),
                ),
        )

    /** Only presets found: the other groups are left out. */
    val presetsOnly =
        SearchModel(
            query = "bloom",
            results =
                SearchResults.Found(
                    query = "bloom",
                    songs = emptyList(),
                    playlists = emptyList(),
                    presets = listOf(PresetResult(PresetId("Slow bloom"), "Slow bloom"), PresetResult(PresetId("Bloom"), "Bloom")),
                ),
        )

    val nothingFound = SearchModel("zzz", SearchResults.Found("zzz", emptyList(), emptyList(), emptyList()))
}
