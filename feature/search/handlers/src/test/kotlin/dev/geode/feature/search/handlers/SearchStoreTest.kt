package dev.geode.feature.search.handlers

import dev.geode.engine.types.Millis
import dev.geode.engine.types.PresetId
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.library.api.LibraryCommand
import dev.geode.feature.library.api.LibraryState
import dev.geode.feature.library.api.Pick
import dev.geode.feature.library.api.Song
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlaybackState
import dev.geode.feature.playback.api.PlaylistId
import dev.geode.feature.playback.api.PlaylistSummary
import dev.geode.feature.search.api.PlaylistRef
import dev.geode.feature.search.api.PlaylistResult
import dev.geode.feature.search.api.PresetResult
import dev.geode.feature.search.api.SearchCommand
import dev.geode.feature.search.api.SearchResults
import dev.geode.feature.search.api.SongResult
import dev.geode.feature.search.core.SEARCH_FEATURE
import dev.geode.feature.search.core.WorkFailure
import dev.geode.kernel.FakeClock
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Search end to end over recording Library, Playback and Customization stores: what they hold is
 * followed, typing settles on the clock, and each pick reaches the feature that owns it.
 */
class SearchStoreTest {
    private val alpha = song("a", "Alpha", artist = "Ann")
    private val gamma = song("c", "Gamma", artist = "Ann", path = "/music/jazz/c.mp3")
    private val night = PresetResult(PresetId("Night drive"), "Night drive")

    private val library = FakeLibrary(LibraryState(songs = listOf(gamma, alpha)))
    private val playback = FakePlayback(PlaybackState(playlists = listOf(PlaylistSummary(PlaylistId(4), "Annual mix", 9))))
    private val customization = FakeCustomization()
    private val presets = MutableStateFlow(listOf(night))
    private val clock = FakeClock()
    private val failures = mutableListOf<FeatureFailure>()

    private val search = searchStore(library, playback, customization, presets, inPlaceRuntime(clock, failures))

    private fun resultOf(song: Song) = SongResult(song.id, song.title, song.artist, song.album, song.genre, song.folder)

    private fun typeAndPause(query: String) {
        search.send(SearchCommand.Type(query))
        clock.advanceBy(Millis(250))
    }

    @Test
    fun `typing searches the Library, Playback and the presets once it pauses`() {
        search.send(SearchCommand.Type("ann"))
        clock.advanceBy(Millis(249))

        assertEquals("ann", search.state.value.query)
        assertEquals(SearchResults.NotSearching, search.state.value.results)

        clock.advanceBy(Millis(1))

        assertEquals(
            SearchResults.Found(
                "ann",
                listOf(resultOf(alpha), resultOf(gamma)),
                listOf(PlaylistResult(PlaylistRef(4), "Annual mix", 9)),
                emptyList(),
            ),
            search.state.value.results,
        )
    }

    @Test
    fun `results follow the Library, Playback and the presets as they change`() {
        typeAndPause("n")

        library.mutableState.value = LibraryState(songs = listOf(alpha, gamma), excludedFolders = setOf("/music/jazz"))
        playback.mutableState.value = PlaybackState()
        presets.value = emptyList()

        assertEquals(SearchResults.Found("n", listOf(resultOf(alpha)), emptyList(), emptyList()), search.state.value.results)
    }

    @Test
    fun `each pick reaches the feature that owns it`() {
        typeAndPause("n")

        search.send(SearchCommand.PlaySong(gamma.id))
        search.send(SearchCommand.QueueSong(alpha.id))
        search.send(SearchCommand.PlayPlaylist(PlaylistRef(4)))
        search.send(SearchCommand.ApplyPreset(night.id))

        assertEquals(
            listOf(
                LibraryCommand.Play(Pick.Songs(listOf(alpha.id, gamma.id)), from = gamma.id),
                LibraryCommand.AddToQueue(Pick.Songs(listOf(alpha.id))),
            ),
            library.sent,
        )
        assertEquals(listOf<PlaybackCommand>(PlaybackCommand.PlayPlaylist(PlaylistId(4))), playback.sent)
        assertEquals(listOf<CustomizationCommand>(CustomizationCommand.LoadPreset(night.id)), customization.sent)
        assertEquals(emptyList<FeatureFailure>(), failures)
    }

    @Test
    fun `clearing empties the field and drops a search not yet run`() {
        search.send(SearchCommand.Type("ann"))
        search.send(SearchCommand.Clear)
        clock.advanceBy(Millis(250))

        assertEquals("", search.state.value.query)
        assertEquals(SearchResults.NotSearching, search.state.value.results)
    }

    @Test
    fun `a feature that refuses a pick is reported`() {
        typeAndPause("n")
        library.failNextSend = IllegalStateException("gone")
        playback.failNextSend = IllegalStateException("gone")
        customization.failNextSend = IllegalStateException("gone")

        search.send(SearchCommand.PlaySong(alpha.id))
        search.send(SearchCommand.PlayPlaylist(PlaylistRef(4)))
        search.send(SearchCommand.ApplyPreset(night.id))

        assertEquals(
            listOf(WorkFailure.LIBRARY_REFUSED, WorkFailure.PLAYBACK_REFUSED, WorkFailure.CUSTOMIZATION_REFUSED)
                .map { FeatureFailure(SEARCH_FEATURE, it.message) },
            failures,
        )
    }
}
