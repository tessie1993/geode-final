package dev.geode.feature.search.core

import dev.geode.engine.types.PresetId
import dev.geode.engine.types.TrackId
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.library.api.LibraryCommand
import dev.geode.feature.library.api.Pick
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaylistId
import dev.geode.feature.search.api.PlaylistRef
import dev.geode.feature.search.api.SearchCatalogue
import dev.geode.feature.search.api.SearchCommand
import dev.geode.feature.search.api.SearchResults
import dev.geode.feature.search.api.SearchState
import dev.geode.kernel.CancelTimer
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.Reducer
import dev.geode.kernel.ReportFailure
import dev.geode.kernel.StartTimer

/**
 * Global Search's state machine (#143).
 *
 * The field follows every keystroke; the search itself runs once typing has paused for
 * [SETTLE_DELAY], as today, and the results shown until then are the last ones found. What can be
 * found follows the Library, Playback and the presets as they change, and results already shown are
 * found again over the new lists.
 *
 * Picking a result sends one Command to the feature that owns it: a song is played (with every song
 * found as the queue) or queued through the Library, a playlist played by Playback, a preset applied by
 * Customization. Only what is shown can be picked.
 */
object SearchReducer : Reducer<SearchState, SearchEvent, SearchEffect> {
    /** What the store carries out once, when it is built. */
    val start: List<SearchEffect> =
        listOf(Perform(SearchWork.FollowLibrary), Perform(SearchWork.FollowPlaylists), Perform(SearchWork.FollowPresets))

    override fun reduce(
        state: SearchState,
        event: SearchEvent,
    ): Next<SearchState, SearchEffect> =
        when (event) {
            is SearchEvent.Command -> command(state, event.command)
            is SearchEvent.Settled ->
                if (event.query == state.query) Next(state.copy(results = SearchMatch.found(event.query, state.catalogue))) else Next(state)
            is SearchEvent.LibraryChanged ->
                refound(state, state.catalogue.copy(songs = SearchMatch.songs(event.songs, event.excludedFolders)))
            is SearchEvent.PlaylistsChanged -> refound(state, state.catalogue.copy(playlists = SearchMatch.playlists(event.playlists)))
            is SearchEvent.PresetsChanged -> refound(state, state.catalogue.copy(presets = event.presets))
            is SearchEvent.WorkFailed -> Next(state, listOf(ReportFailure(event.failure.message)))
        }

    private fun command(
        state: SearchState,
        command: SearchCommand,
    ): Next<SearchState, SearchEffect> =
        when (command) {
            is SearchCommand.Type -> type(state, command.query)
            SearchCommand.Clear -> type(state, "")
            is SearchCommand.PlaySong -> playSong(state, command.song)
            is SearchCommand.QueueSong ->
                pick(state, state.shown { found -> found.songs.any { it.id == command.song } }) {
                    SearchWork.TellLibrary(LibraryCommand.AddToQueue(Pick.Songs(listOf(command.song))))
                }
            is SearchCommand.PlayPlaylist -> playPlaylist(state, command.playlist)
            is SearchCommand.ApplyPreset -> applyPreset(state, command.preset)
        }

    private fun type(
        state: SearchState,
        query: String,
    ): Next<SearchState, SearchEffect> =
        when {
            query == state.query -> Next(state)
            SearchMatch.terms(query).isEmpty() ->
                Next(state.copy(query = query, results = SearchResults.NotSearching), listOf(CancelTimer(SettleTimer)))
            else -> Next(state.copy(query = query), listOf(StartTimer(SettleTimer, SETTLE_DELAY, SearchEvent.Settled(query))))
        }

    private fun playSong(
        state: SearchState,
        song: TrackId,
    ): Next<SearchState, SearchEffect> {
        val found = state.results as? SearchResults.Found ?: return Next(state)
        val ids = found.songs.map { it.id }
        return pick(state, song in ids) { SearchWork.TellLibrary(LibraryCommand.Play(Pick.Songs(ids), from = song)) }
    }

    private fun playPlaylist(
        state: SearchState,
        playlist: PlaylistRef,
    ): Next<SearchState, SearchEffect> =
        pick(state, state.shown { found -> found.playlists.any { it.id == playlist } }) {
            SearchWork.TellPlayback(PlaybackCommand.PlayPlaylist(PlaylistId(playlist.value)))
        }

    private fun applyPreset(
        state: SearchState,
        preset: PresetId,
    ): Next<SearchState, SearchEffect> =
        pick(state, state.shown { found -> found.presets.any { it.id == preset } }) {
            SearchWork.TellCustomization(CustomizationCommand.LoadPreset(preset))
        }

    /** [work] when [shown], nothing otherwise. */
    private inline fun pick(
        state: SearchState,
        shown: Boolean,
        work: () -> SearchWork,
    ): Next<SearchState, SearchEffect> = if (shown) Next(state, listOf(Perform(work()))) else Next(state)

    /** Whether results are shown and [test] holds for them. */
    private inline fun SearchState.shown(test: (SearchResults.Found) -> Boolean): Boolean {
        val found = results as? SearchResults.Found ?: return false
        return test(found)
    }

    /** [state] over [catalogue], with the results shown found again in it. */
    private fun refound(
        state: SearchState,
        catalogue: SearchCatalogue,
    ): Next<SearchState, SearchEffect> {
        val results =
            when (val shown = state.results) {
                SearchResults.NotSearching -> shown
                is SearchResults.Found -> SearchMatch.found(shown.query, catalogue)
            }
        return Next(state.copy(catalogue = catalogue, results = results))
    }
}
