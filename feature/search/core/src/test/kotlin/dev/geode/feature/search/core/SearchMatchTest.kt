package dev.geode.feature.search.core

import dev.geode.feature.playback.api.PlaylistId
import dev.geode.feature.playback.api.PlaylistSummary
import dev.geode.feature.search.api.PlaylistRef
import dev.geode.feature.search.api.PlaylistResult
import dev.geode.feature.search.api.PresetResult
import dev.geode.feature.search.api.SearchCatalogue
import dev.geode.feature.search.api.SearchResults
import dev.geode.feature.search.api.SongResult
import dev.geode.feature.search.core.Fixtures.alpha
import dev.geode.feature.search.core.Fixtures.beta
import dev.geode.feature.search.core.Fixtures.bloom
import dev.geode.feature.search.core.Fixtures.calm
import dev.geode.feature.search.core.Fixtures.catalogue
import dev.geode.feature.search.core.Fixtures.gamma
import dev.geode.feature.search.core.Fixtures.night
import dev.geode.feature.search.core.Fixtures.result
import dev.geode.feature.search.core.Fixtures.road
import dev.geode.feature.search.core.Fixtures.song
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Today's matching (the old Search overlay's `SearchMatcher`), kept as it was: the query is split into
 * words at spaces, and a result matches when every word is found, ignoring case, somewhere in its
 * fields. Songs match on title, artist, album, genre and folder; playlists and presets on their name.
 */
class SearchMatchTest {
    private fun found(
        query: String,
        songs: List<SongResult> = emptyList(),
        playlists: List<PlaylistResult> = emptyList(),
        presets: List<PresetResult> = emptyList(),
    ) = SearchResults.Found(query, songs, playlists, presets)

    @Test
    fun `the query is split into words at any run of spaces`() {
        assertEquals(listOf("ann", "first"), SearchMatch.terms("  ann \t first  "))
        assertEquals(emptyList<String>(), SearchMatch.terms(""))
        assertEquals(emptyList<String>(), SearchMatch.terms("   "))
    }

    @Test
    fun `nothing to search for finds nothing`() {
        assertEquals(SearchResults.NotSearching, SearchMatch.found("", catalogue))
        assertEquals(SearchResults.NotSearching, SearchMatch.found("  ", catalogue))
    }

    @Test
    fun `a word is found anywhere in a field, ignoring case`() {
        assertEquals(found("LPH", songs = listOf(alpha.result())), SearchMatch.found("LPH", catalogue))
        assertEquals(found("blues", songs = listOf(beta.result())), SearchMatch.found("blues", catalogue))
    }

    @Test
    fun `songs match on their title, artist, album, genre and folder`() {
        assertEquals(found("ann", songs = listOf(alpha.result(), gamma.result())), SearchMatch.found("ann", catalogue))
        assertEquals(found("second", songs = listOf(beta.result())), SearchMatch.found("second", catalogue))
        assertEquals(found("jazz", songs = listOf(gamma.result())), SearchMatch.found("jazz", catalogue))
        assertEquals(found("/music/blues", songs = listOf(beta.result())), SearchMatch.found("/music/blues", catalogue))
    }

    @Test
    fun `every word must match, each in any field`() {
        assertEquals(found("ann third", songs = listOf(gamma.result())), SearchMatch.found("ann third", catalogue))
        assertEquals(found("ann blues"), SearchMatch.found("ann blues", catalogue))
    }

    @Test
    fun `playlists and presets match on their name`() {
        assertEquals(found("trip", playlists = listOf(road)), SearchMatch.found("trip", catalogue))
        assertEquals(found("EVEN", playlists = listOf(calm)), SearchMatch.found("EVEN", catalogue))
        assertEquals(found("bloom", presets = listOf(bloom)), SearchMatch.found("bloom", catalogue))
    }

    @Test
    fun `one query finds songs, playlists and presets together`() {
        assertEquals(
            found(
                "i",
                songs = listOf(alpha.result(), beta.result(), gamma.result()),
                playlists = listOf(calm, road),
                presets = listOf(night),
            ),
            SearchMatch.found("i", catalogue),
        )
    }

    @Test
    fun `at most 30 songs are shown, the first by title`() {
        val many = (1..40).map { song("s$it", "Song %02d".format(it)).result() }

        val shown = SearchMatch.found("song", SearchCatalogue(songs = many)) as SearchResults.Found

        assertEquals(SearchResults.SONG_LIMIT, shown.songs.size)
        assertEquals(many.take(SearchResults.SONG_LIMIT), shown.songs)
    }

    @Test
    fun `the songs searched are by title, ignoring case, without the excluded folders`() {
        val ringtone = song("r", "Aardvark ring", path = "/ringtones/r.ogg")
        val deeper = song("d", "Deep tone", path = "/ringtones/old/d.ogg")
        val near = song("n", "Near miss", path = "/ringtones-kept/n.mp3")

        assertEquals(
            listOf(alpha.result(), beta.result(), gamma.result(), near.result()),
            SearchMatch.songs(listOf(gamma, near, beta, ringtone, alpha, deeper), setOf("/ringtones")),
        )
    }

    @Test
    fun `the playlists searched are Playback's, by name, ignoring case`() {
        assertEquals(
            listOf(PlaylistResult(PlaylistRef(2), "calm evenings", 4), PlaylistResult(PlaylistRef(1), "Road trip", 12)),
            SearchMatch.playlists(
                listOf(PlaylistSummary(PlaylistId(1), "Road trip", 12), PlaylistSummary(PlaylistId(2), "calm evenings", 4)),
            ),
        )
    }
}
