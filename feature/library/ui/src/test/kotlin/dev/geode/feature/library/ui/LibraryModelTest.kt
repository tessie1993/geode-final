package dev.geode.feature.library.ui

import dev.geode.engine.types.Millis
import dev.geode.feature.library.api.GroupKey
import dev.geode.feature.library.api.GroupKind
import dev.geode.feature.library.api.LibraryState
import dev.geode.feature.library.api.LibraryView
import dev.geode.feature.library.api.Place
import dev.geode.feature.library.api.RuleField
import dev.geode.feature.library.api.RuleOp
import dev.geode.feature.library.api.SmartPlaylist
import dev.geode.feature.library.api.SmartRule
import dev.geode.feature.library.api.SongEdit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryModelTest {
    @Test
    fun `the model shows the state's place, search and page`() {
        val state = LibraryState(place = Place.Group(LibraryView.ARTISTS, GroupKey(GroupKind.ARTIST, "Ann")), query = "x")
        val model = state.toLibraryModel()

        assertEquals(LibraryView.ARTISTS, model.view)
        assertEquals(state.page, model.page)
        assertTrue(model.canGoBack)
    }

    @Test
    fun `Back leaves a search or a group, not the top of a view`() {
        assertFalse(LibraryModel.EMPTY.canGoBack)
        assertEquals(LibraryView.SONGS, LibraryModel.EMPTY.view)
        assertTrue(LibraryState(query = "a").toLibraryModel().canGoBack)
    }

    @Test
    fun `lengths print as minutes and seconds, with hours past an hour`() {
        assertEquals("0:00", formatLength(Millis(-5)))
        assertEquals("3:07", formatLength(Millis(187_000)))
        assertEquals("1:02:09", formatLength(Millis(3_729_000)))
    }

    @Test
    fun `the editor needs a title, and saves what it holds`() {
        val fields = SongEditorFields.of(PreviewModels.night)

        assertTrue(fields.canSave)
        assertFalse(fields.copy(title = " ").canSave)
        assertEquals("Night Drive", fields.toEdit(PreviewModels.night).title)
    }

    @Test
    fun `the editor leaves the tags the user did not change as they were`() {
        val night = PreviewModels.night
        val fields = SongEditorFields.of(night)

        assertEquals(SongEdit(night.title, night.artist, night.album, night.artwork), fields.toEdit(night))
        assertEquals(
            SongEdit(night.title, night.artist, night.album, night.artwork, genre = "Soul", year = 1999, trackNumber = 0, comment = "live"),
            fields.copy(genre = "Soul", year = "1999", trackNumber = "", comment = "live").toEdit(night.copy(trackNumber = 4)),
        )
    }

    @Test
    fun `the model knows the smart playlists, and which names an edited one may not take`() {
        val long = PreviewModels.longFavourites
        val model = LibraryState(smartPlaylists = listOf(long, SmartPlaylist("Other"))).toLibraryModel()

        assertEquals(long, model.smartPlaylist("Long favourites"))
        assertEquals(null, model.smartPlaylist("Gone"))
        assertEquals(setOf("Other"), model.takenSmartNames(long))
        assertEquals(setOf("Long favourites", "Other"), model.takenSmartNames(null))
    }

    @Test
    fun `the smart playlist editor opens on a playlist, or blank for a new one`() {
        val fields = SmartPlaylistFields.of(PreviewModels.longFavourites)

        assertEquals("Long favourites", fields.name)
        assertEquals("25", fields.limit)
        assertEquals(PreviewModels.longFavourites, fields.toPlaylist())
        assertEquals(SmartPlaylistFields("", emptyList(), matchAll = true, limit = ""), SmartPlaylistFields.of(null))
        assertEquals(SmartPlaylist("Mix"), SmartPlaylistFields.of(null).copy(name = " Mix ").toPlaylist())
    }

    @Test
    fun `a smart playlist needs a name no other one has`() {
        val fields = SmartPlaylistFields.of(null).copy(name = " Mix ")

        assertTrue(fields.canSave(setOf("Other")))
        assertTrue(fields.nameTaken(setOf("Mix")))
        assertFalse(fields.canSave(setOf("Mix")))
        assertFalse(fields.copy(name = "  ").canSave(emptySet()))
    }

    @Test
    fun `rules are added, changed and removed, and the limit takes digits only`() {
        val added = SmartPlaylistFields.of(null).withNewRule().withNewRule()
        val artist = SmartRule(RuleField.ARTIST, RuleOp.CONTAINS, "")
        assertEquals(listOf(artist, artist), added.rules)

        val title = SmartRule(RuleField.TITLE, RuleOp.IS, "x")
        val changed = added.withRule(1, title)
        assertEquals(listOf(artist, title), changed.rules)
        assertEquals(listOf(title), changed.withoutRule(0).rules)

        assertEquals("12", added.withLimit("1a2 ").limit)
        assertEquals(12, added.withLimit("12").toPlaylist().limit)
        assertEquals(0, added.withLimit("").toPlaylist().limit)
    }

    @Test
    fun `choosing a rule's field starts it on that field's comparison, keeping the value`() {
        val rule = SmartRule(RuleField.TITLE, RuleOp.IS, "3")

        assertEquals(SmartRule(RuleField.PLAY_COUNT, RuleOp.AT_LEAST, "3"), SmartPlaylistFields.withField(rule, RuleField.PLAY_COUNT))
        assertEquals(SmartRule(RuleField.FAVOURITE, RuleOp.IS, "3"), SmartPlaylistFields.withField(rule, RuleField.FAVOURITE))
        assertEquals(rule, SmartPlaylistFields.withField(rule, RuleField.TITLE))
    }
}
