package dev.geode.feature.library.ui

import dev.geode.engine.types.Millis
import dev.geode.feature.library.api.GroupKey
import dev.geode.feature.library.api.GroupKind
import dev.geode.feature.library.api.LibraryState
import dev.geode.feature.library.api.LibraryView
import dev.geode.feature.library.api.Place
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
        assertEquals("Night Drive", fields.toEdit().title)
    }
}
