package dev.geode.feature.search.api

import dev.geode.engine.types.PresetId
import dev.geode.engine.types.TrackId
import dev.geode.kernel.Feature

/**
 * Global Search as everything outside it sees it (ADR-0003, ADR-0005): one search across the songs,
 * the playlists and the presets (#143), opened from Home's Search shortcut.
 *
 * It owns none of what it finds. The songs are the Library's, the playlists Playback's and the presets
 * Customization's; picking a result sends the owning feature a Command through its `:api`, so playing
 * a song or a playlist goes through Playback's queue and applying a preset through Customization.
 * It announces nothing of its own.
 */
typealias Search = Feature<SearchState, SearchCommand, Nothing>

/**
 * One song Search can find: what it shows ([title], and [artist] and [album] under it) and what it also
 * matches ([genre], and the [folder] the file is in).
 */
data class SongResult(
    val id: TrackId,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val folder: String,
)

/** A playlist, which Playback keeps (ADR-0005 data ownership). */
@JvmInline
value class PlaylistRef(
    val value: Long,
) {
    override fun toString(): String = "playlist $value"
}

data class PlaylistResult(
    val id: PlaylistRef,
    val name: String,
    val trackCount: Int,
)

/** A preset, built in or saved, which Customization applies. */
data class PresetResult(
    val id: PresetId,
    val name: String,
)

/** What Search shows under the search field. */
sealed interface SearchResults {
    /** Nothing to search for yet: the field is empty, or holds only spaces. */
    data object NotSearching : SearchResults

    /**
     * What [query] found, grouped: at most [SONG_LIMIT] [songs] by title, the [playlists] by name and
     * the [presets] in the order Customization lists them.
     */
    data class Found(
        val query: String,
        val songs: List<SongResult>,
        val playlists: List<PlaylistResult>,
        val presets: List<PresetResult>,
    ) : SearchResults {
        val isEmpty: Boolean get() = songs.isEmpty() && playlists.isEmpty() && presets.isEmpty()
    }

    companion object {
        /** The most songs one search shows, as today's Search did. */
        const val SONG_LIMIT = 30
    }
}

/**
 * Everything that can be searched, as the owning features last said: the Library's [songs] (by title,
 * without the folders the person excluded), Playback's [playlists] (by name) and the [presets]. Only
 * Search's reducer reads it.
 */
data class SearchCatalogue(
    val songs: List<SongResult> = emptyList(),
    val playlists: List<PlaylistResult> = emptyList(),
    val presets: List<PresetResult> = emptyList(),
)

/**
 * Everything Search lets the outside read: the [query] as typed, and the [results] of the search,
 * which follow the query once typing has paused.
 */
data class SearchState(
    val query: String = "",
    val results: SearchResults = SearchResults.NotSearching,
    val catalogue: SearchCatalogue = SearchCatalogue(),
)

/**
 * What the outside may ask of Search. A pick names one of the results shown; a pick of anything else
 * does nothing.
 */
sealed interface SearchCommand {
    /** The search field now holds [query]. */
    data class Type(
        val query: String,
    ) : SearchCommand

    /** Empties the search field, as when Search is closed. */
    data object Clear : SearchCommand

    /** Plays every song found, in order, from [song]. */
    data class PlaySong(
        val song: TrackId,
    ) : SearchCommand

    /** Adds [song] to the end of the queue. */
    data class QueueSong(
        val song: TrackId,
    ) : SearchCommand

    /** Plays the playlist from its start. */
    data class PlayPlaylist(
        val playlist: PlaylistRef,
    ) : SearchCommand

    /** Applies the preset to the Visual. */
    data class ApplyPreset(
        val preset: PresetId,
    ) : SearchCommand
}
