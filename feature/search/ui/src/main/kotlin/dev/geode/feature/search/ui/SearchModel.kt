package dev.geode.feature.search.ui

import androidx.compose.runtime.Immutable
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.TrackId
import dev.geode.feature.search.api.PlaylistRef
import dev.geode.feature.search.api.SearchCommand
import dev.geode.feature.search.api.SearchResults
import dev.geode.feature.search.api.SearchState

/** What the Search screen shows: the [query] as typed, and the [results] found for it. */
@Immutable
data class SearchModel(
    val query: String,
    val results: SearchResults,
)

/** Search's screen model: what the store holds, without the lists it searches. */
fun SearchState.toSearchModel(): SearchModel = SearchModel(query, results)

/** Everything the person can do on the Search screen. */
sealed interface SearchAction {
    /** The field now holds [query]; the clear button types an empty one. */
    data class Type(
        val query: String,
    ) : SearchAction

    data class PlaySong(
        val song: TrackId,
    ) : SearchAction

    data class QueueSong(
        val song: TrackId,
    ) : SearchAction

    data class PlayPlaylist(
        val playlist: PlaylistRef,
    ) : SearchAction

    data class ApplyPreset(
        val preset: PresetId,
    ) : SearchAction

    /** Leaves Search. */
    data object Close : SearchAction
}

/** The Command an action sends; null for [SearchAction.Close], which only leaves. */
fun SearchAction.toCommand(): SearchCommand? =
    when (this) {
        is SearchAction.Type -> SearchCommand.Type(query)
        is SearchAction.PlaySong -> SearchCommand.PlaySong(song)
        is SearchAction.QueueSong -> SearchCommand.QueueSong(song)
        is SearchAction.PlayPlaylist -> SearchCommand.PlayPlaylist(playlist)
        is SearchAction.ApplyPreset -> SearchCommand.ApplyPreset(preset)
        SearchAction.Close -> null
    }

/**
 * Whether the action leaves Search: closing, and picking a song, a playlist or a preset, as today.
 * Typing and adding a song to the queue stay.
 */
val SearchAction.leaves: Boolean
    get() =
        when (this) {
            is SearchAction.Type, is SearchAction.QueueSong -> false
            is SearchAction.PlaySong, is SearchAction.PlayPlaylist, is SearchAction.ApplyPreset, SearchAction.Close -> true
        }
