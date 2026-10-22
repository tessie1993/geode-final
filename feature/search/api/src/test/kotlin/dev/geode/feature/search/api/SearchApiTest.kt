package dev.geode.feature.search.api

import dev.geode.engine.types.PresetId
import dev.geode.engine.types.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchApiTest {
    private val song = SongResult(TrackId("content://a"), "Alpha", "Ann", "First", "Rock", "/music")
    private val playlist = PlaylistResult(PlaylistRef(1), "Road", 3)
    private val preset = PresetResult(PresetId("Night drive"), "Night drive")

    @Test
    fun `a search found nothing only when every group is empty`() {
        assertTrue(SearchResults.Found("x", emptyList(), emptyList(), emptyList()).isEmpty)
        assertFalse(SearchResults.Found("x", listOf(song), emptyList(), emptyList()).isEmpty)
        assertFalse(SearchResults.Found("x", emptyList(), listOf(playlist), emptyList()).isEmpty)
        assertFalse(SearchResults.Found("x", emptyList(), emptyList(), listOf(preset)).isEmpty)
    }

    @Test
    fun `a playlist reference reads as one`() {
        assertEquals("playlist 7", PlaylistRef(7).toString())
    }

    @Test
    fun `Search starts empty, showing nothing`() {
        assertEquals(SearchState("", SearchResults.NotSearching, SearchCatalogue()), SearchState())
    }
}
