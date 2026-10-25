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

    @Test
    fun `smart playlist rules compare words, numbers and yes-or-no fields with the comparisons each allows`() {
        val words = listOf(RuleField.TITLE, RuleField.ARTIST, RuleField.ALBUM, RuleField.FOLDER)
        val numbers = listOf(RuleField.DURATION_MINUTES, RuleField.ADDED_DAYS_AGO, RuleField.PLAY_COUNT)

        assertEquals(words, RuleField.entries.filter { it.isText })
        assertEquals(listOf(RuleField.FAVOURITE), RuleField.entries.filter { it.isFlag })
        for (field in words) {
            assertEquals(listOf(RuleOp.CONTAINS, RuleOp.IS, RuleOp.IS_NOT), field.ops)
            assertEquals(RuleOp.CONTAINS, field.defaultOp)
        }
        for (field in numbers) {
            assertEquals(listOf(RuleOp.IS, RuleOp.AT_LEAST, RuleOp.AT_MOST), field.ops)
            assertEquals(RuleOp.AT_LEAST, field.defaultOp)
        }
        assertEquals(listOf(RuleOp.IS, RuleOp.IS_NOT), RuleField.FAVOURITE.ops)
        assertEquals(RuleOp.IS, RuleField.FAVOURITE.defaultOp)
        assertEquals(listOf(RuleOp.CONTAINS, RuleOp.IS, RuleOp.IS_NOT), RuleOp.entries.filter { it.forText })
        assertEquals(listOf(RuleOp.IS, RuleOp.AT_LEAST, RuleOp.AT_MOST), RuleOp.entries.filter { it.forNumber })
    }

    @Test
    fun `a new smart playlist matches every rule, with no limit, and the Library has none until it reads them`() {
        val playlist = SmartPlaylist("Long ones")

        assertEquals(emptyList<SmartRule>(), playlist.rules)
        assertTrue(playlist.matchAll)
        assertEquals(0, playlist.limit)
        assertEquals(emptyList<SmartPlaylist>(), LibraryState().smartPlaylists)
        assertEquals(null, LibraryState().now)
    }
}
