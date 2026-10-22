package dev.geode.feature.search.core

import dev.geode.engine.types.PresetId
import dev.geode.engine.types.TrackId
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.library.api.LibraryCommand
import dev.geode.feature.library.api.Pick
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaylistId
import dev.geode.feature.playback.api.PlaylistSummary
import dev.geode.feature.search.api.PlaylistRef
import dev.geode.feature.search.api.PlaylistResult
import dev.geode.feature.search.api.SearchCatalogue
import dev.geode.feature.search.api.SearchCommand
import dev.geode.feature.search.api.SearchResults
import dev.geode.feature.search.api.SearchState
import dev.geode.feature.search.core.Fixtures.alpha
import dev.geode.feature.search.core.Fixtures.beta
import dev.geode.feature.search.core.Fixtures.bloom
import dev.geode.feature.search.core.Fixtures.calm
import dev.geode.feature.search.core.Fixtures.catalogue
import dev.geode.feature.search.core.Fixtures.command
import dev.geode.feature.search.core.Fixtures.gamma
import dev.geode.feature.search.core.Fixtures.night
import dev.geode.feature.search.core.Fixtures.reduce
import dev.geode.feature.search.core.Fixtures.result
import dev.geode.feature.search.core.Fixtures.road
import dev.geode.kernel.CancelTimer
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure
import dev.geode.kernel.StartTimer
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Typing, settling, following what the owning features hold, and picking a result, as a transition
 * table (ADR-0003): each row is a state, an event, and the Next it must give.
 */
class SearchReducerTest {
    /** Nothing typed, everything known. */
    private val idle = SearchState(catalogue = catalogue)

    /** "ann" typed, not yet settled. */
    private val typing = idle.copy(query = "ann")

    private val annFound = SearchResults.Found("ann", listOf(alpha.result(), gamma.result()), emptyList(), emptyList())

    /** "ann" typed and settled: two songs. */
    private val showingAnn = typing.copy(results = annFound)

    /** "i" settled: songs, both playlists and one preset. */
    private val showingI = idle.copy(query = "i", results = SearchMatch.found("i", catalogue))

    private fun same(state: SearchState) = Next<SearchState, SearchEffect>(state)

    private fun settleLater(query: String) = StartTimer(SettleTimer, SETTLE_DELAY, SearchEvent.Settled(query))

    private fun tellLibrary(command: LibraryCommand) = Perform(SearchWork.TellLibrary(command))

    private data class Row(
        val name: String,
        val state: SearchState,
        val event: SearchEvent,
        val next: Next<SearchState, SearchEffect>,
    )

    private val table: List<Row> =
        listOf(
            // Typing: the field follows at once, the search once typing pauses.
            Row("type a word", idle, command(SearchCommand.Type("ann")), Next(typing, listOf(settleLater("ann")))),
            Row(
                "type on while results show",
                showingAnn,
                command(SearchCommand.Type("ann t")),
                Next(showingAnn.copy(query = "ann t"), listOf(settleLater("ann t"))),
            ),
            Row(
                "empty the field",
                showingAnn,
                command(SearchCommand.Type("")),
                Next(idle, listOf(CancelTimer(SettleTimer))),
            ),
            Row(
                "only spaces",
                typing,
                command(SearchCommand.Type("   ")),
                Next(idle.copy(query = "   "), listOf(CancelTimer(SettleTimer))),
            ),
            Row("type what is already there", typing, command(SearchCommand.Type("ann")), same(typing)),
            Row("clear", showingAnn, command(SearchCommand.Clear), Next(idle, listOf(CancelTimer(SettleTimer)))),
            // Settling searches what was typed; a settle for older text is stale.
            Row("typing pauses", typing, SearchEvent.Settled("ann"), same(showingAnn)),
            Row("a stale settle", typing, SearchEvent.Settled("an"), same(typing)),
            Row(
                "a settle that finds nothing",
                idle.copy(query = "zzz"),
                SearchEvent.Settled("zzz"),
                same(idle.copy(query = "zzz", results = SearchResults.Found("zzz", emptyList(), emptyList(), emptyList()))),
            ),
            // What can be found follows the Library, Playback and the presets; results shown follow too.
            Row(
                "the Library changes while results show",
                showingAnn,
                SearchEvent.LibraryChanged(listOf(gamma, beta), emptySet()),
                same(
                    showingAnn.copy(
                        catalogue = catalogue.copy(songs = listOf(beta.result(), gamma.result())),
                        results = annFound.copy(songs = listOf(gamma.result())),
                    ),
                ),
            ),
            Row(
                "a folder is excluded while nothing is searched",
                idle,
                SearchEvent.LibraryChanged(listOf(alpha, beta, gamma), setOf("/music/jazz")),
                same(idle.copy(catalogue = catalogue.copy(songs = listOf(alpha.result(), beta.result())))),
            ),
            Row(
                "Playback's playlists change while results show",
                showingI,
                SearchEvent.PlaylistsChanged(listOf(PlaylistSummary(PlaylistId(3), "Mix", 2))),
                same(
                    showingI.copy(
                        catalogue = catalogue.copy(playlists = listOf(PlaylistResult(PlaylistRef(3), "Mix", 2))),
                        results =
                            (showingI.results as SearchResults.Found).copy(
                                playlists = listOf(PlaylistResult(PlaylistRef(3), "Mix", 2)),
                            ),
                    ),
                ),
            ),
            Row(
                "the presets change while results show",
                showingI,
                SearchEvent.PresetsChanged(listOf(bloom)),
                same(
                    showingI.copy(
                        catalogue = catalogue.copy(presets = listOf(bloom)),
                        results = (showingI.results as SearchResults.Found).copy(presets = emptyList()),
                    ),
                ),
            ),
            Row(
                "the presets change while typing has not settled",
                typing,
                SearchEvent.PresetsChanged(emptyList()),
                same(typing.copy(catalogue = catalogue.copy(presets = emptyList()))),
            ),
            // Picking a result: a Command to the feature that owns it, through its :api.
            Row(
                "play a song found",
                showingAnn,
                command(SearchCommand.PlaySong(gamma.id)),
                Next(showingAnn, listOf(tellLibrary(LibraryCommand.Play(Pick.Songs(listOf(alpha.id, gamma.id)), from = gamma.id)))),
            ),
            Row(
                "queue a song found",
                showingAnn,
                command(SearchCommand.QueueSong(alpha.id)),
                Next(showingAnn, listOf(tellLibrary(LibraryCommand.AddToQueue(Pick.Songs(listOf(alpha.id)))))),
            ),
            Row(
                "play a playlist found",
                showingI,
                command(SearchCommand.PlayPlaylist(road.id)),
                Next(showingI, listOf(Perform(SearchWork.TellPlayback(PlaybackCommand.PlayPlaylist(PlaylistId(1)))))),
            ),
            Row(
                "apply a preset found",
                showingI,
                command(SearchCommand.ApplyPreset(night.id)),
                Next(showingI, listOf(Perform(SearchWork.TellCustomization(CustomizationCommand.LoadPreset(night.id))))),
            ),
            // A pick of something not shown does nothing.
            Row("play a song not found", showingAnn, command(SearchCommand.PlaySong(beta.id)), same(showingAnn)),
            Row("queue a song not found", showingAnn, command(SearchCommand.QueueSong(TrackId("gone"))), same(showingAnn)),
            Row("play a playlist not found", showingAnn, command(SearchCommand.PlayPlaylist(calm.id)), same(showingAnn)),
            Row("apply a preset not found", showingI, command(SearchCommand.ApplyPreset(PresetId("gone"))), same(showingI)),
            Row("play a song before anything was found", typing, command(SearchCommand.PlaySong(alpha.id)), same(typing)),
            Row("queue a song before anything was found", typing, command(SearchCommand.QueueSong(alpha.id)), same(typing)),
            Row("play a playlist before anything was found", idle, command(SearchCommand.PlayPlaylist(road.id)), same(idle)),
            Row("apply a preset before anything was found", idle, command(SearchCommand.ApplyPreset(night.id)), same(idle)),
            // Failures are reported on the app-wide channel; what shows stays.
            failure(WorkFailure.LIBRARY_REFUSED),
            failure(WorkFailure.PLAYBACK_REFUSED),
            failure(WorkFailure.CUSTOMIZATION_REFUSED),
        )

    private fun failure(failure: WorkFailure) =
        Row("$failure is reported", showingAnn, SearchEvent.WorkFailed(failure), Next(showingAnn, listOf(ReportFailure(failure.message))))

    @Test
    fun `every row of the table`() {
        for (row in table) {
            assertEquals(row.name, row.next, reduce(row.state, row.event))
        }
    }

    @Test
    fun `the store starts by following the Library, Playback and the presets`() {
        assertEquals(
            listOf(Perform(SearchWork.FollowLibrary), Perform(SearchWork.FollowPlaylists), Perform(SearchWork.FollowPresets)),
            SearchReducer.start,
        )
    }

    @Test
    fun `typing settles after a quarter of a second, as today`() {
        assertEquals(250L, SETTLE_DELAY.value)
    }

    @Test
    fun `every failure has its own message`() {
        assertEquals(
            listOf(
                "could not ask the Library to play or queue a song found",
                "could not ask Playback to play a playlist found",
                "could not ask Customization to apply a preset found",
            ),
            WorkFailure.entries.map { it.message },
        )
    }

    @Test
    fun `an empty catalogue finds nothing for any query`() {
        assertEquals(
            SearchResults.Found("ann", emptyList(), emptyList(), emptyList()),
            reduce(SearchState(query = "ann"), SearchEvent.Settled("ann")).state.results,
        )
        assertEquals(SearchCatalogue(), SearchState().catalogue)
    }
}
