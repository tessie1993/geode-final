package dev.geode.feature.library.api

import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryApiTest {
    private val song =
        Song(
            id = TrackId("content://a"),
            title = "Alpha",
            artist = "Ann",
            album = "First",
            albumArtist = "",
            genre = "Rock",
            composer = "",
            path = "/music/rock/alpha.mp3",
            format = "mp3",
            duration = Millis(200_000),
            addedAt = Millis(1),
        )

    @Test
    fun `a song's folder is the one its file is in`() {
        assertEquals("/music/rock", song.folder)
        assertEquals("", song.copy(path = "alpha.mp3").folder)
    }

    @Test
    fun `a song's album artist falls back to its artist`() {
        assertEquals("Ann", song.shownAlbumArtist)
        assertEquals("Various", song.copy(albumArtist = "Various").shownAlbumArtist)
    }

    @Test
    fun `a search found nothing only when every list is empty`() {
        val nothing = Page.Found("x", emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val group = GroupRow(GroupKey(GroupKind.FOLDER, "/x"), "x", "", 1, null)

        assertTrue(nothing.isEmpty)
        assertFalse(nothing.copy(songs = listOf(SongRow(song, favourite = false, plays = 0))).isEmpty)
        assertFalse(nothing.copy(folders = listOf(group)).isEmpty)
    }

    @Test
    fun `a new Library is loading, at the top of Songs, sorted by name`() {
        val state = LibraryState()

        assertEquals(IndexStatus.Loading, state.index)
        assertEquals(Place.Shelf(LibraryView.SONGS), state.place)
        assertEquals(Sort(SortKey.NAME, descending = false), state.sort)
        assertEquals("playlist 4", PlaylistRef(4).toString())
    }
}
