package dev.geode.feature.search.ui

import dev.geode.engine.types.PresetId
import dev.geode.engine.types.TrackId
import dev.geode.feature.search.api.PlaylistRef
import dev.geode.feature.search.api.SearchCatalogue
import dev.geode.feature.search.api.SearchCommand
import dev.geode.feature.search.api.SearchResults
import dev.geode.feature.search.api.SearchState
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchModelTest {
    private val song = TrackId("content://media/1")
    private val playlist = PlaylistRef(3)
    private val preset = PresetId("Night drive")

    @Test
    fun `the model is what was typed and what it found, without the lists searched`() {
        val state = SearchState("ann", PreviewModels.found.results, SearchCatalogue(presets = emptyList()))

        assertEquals(SearchModel("ann", PreviewModels.found.results), state.toSearchModel())
        assertEquals(SearchModel("", SearchResults.NotSearching), SearchState().toSearchModel())
    }

    @Test
    fun `each action but closing is one Command`() {
        assertEquals(SearchCommand.Type("ann"), SearchAction.Type("ann").toCommand())
        assertEquals(SearchCommand.PlaySong(song), SearchAction.PlaySong(song).toCommand())
        assertEquals(SearchCommand.QueueSong(song), SearchAction.QueueSong(song).toCommand())
        assertEquals(SearchCommand.PlayPlaylist(playlist), SearchAction.PlayPlaylist(playlist).toCommand())
        assertEquals(SearchCommand.ApplyPreset(preset), SearchAction.ApplyPreset(preset).toCommand())
        assertEquals(null, SearchAction.Close.toCommand())
    }

    @Test
    fun `picking a song, a playlist or a preset leaves Search, as today, and typing or queueing stays`() {
        val leaving =
            listOf(
                SearchAction.Type("ann"),
                SearchAction.QueueSong(song),
                SearchAction.PlaySong(song),
                SearchAction.PlayPlaylist(playlist),
                SearchAction.ApplyPreset(preset),
                SearchAction.Close,
            ).map { it.leaves }

        assertEquals(listOf(false, false, true, true, true, true), leaving)
    }
}
