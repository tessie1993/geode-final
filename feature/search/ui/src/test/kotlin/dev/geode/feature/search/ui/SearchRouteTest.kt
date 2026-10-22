package dev.geode.feature.search.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.TrackId
import dev.geode.feature.search.api.PlaylistRef
import dev.geode.feature.search.api.Search
import dev.geode.feature.search.api.SearchCommand
import dev.geode.feature.search.api.SearchState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The route over a recording Search: each tap is one Command, and leaving empties the field first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SearchRouteTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class RecordingSearch(
        initial: SearchState,
    ) : Search {
        override val state: StateFlow<SearchState> = MutableStateFlow(initial)
        override val events: Flow<Nothing> = emptyFlow()
        val sent = mutableListOf<SearchCommand>()

        override fun send(command: SearchCommand) {
            sent += command
        }
    }

    private val search = RecordingSearch(SearchState(PreviewModels.found.query, PreviewModels.found.results))
    private var closed = 0

    private fun show() = compose.setContent { SearchRoute(search, onClose = { closed++ }) }

    private fun scrollTo(text: String) = compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))

    @Test
    fun `typing sends what the field holds and stays`() {
        val empty = RecordingSearch(SearchState())
        compose.setContent { SearchRoute(empty, onClose = { closed++ }) }

        compose.onNode(hasSetTextAction()).performTextInput("ann")

        assertEquals(listOf<SearchCommand>(SearchCommand.Type("ann")), empty.sent)
        assertEquals(0, closed)
    }

    @Test
    fun `queueing a song stays`() {
        show()

        compose.onNodeWithContentDescription("Add Nightfall to the queue").performClick()

        assertEquals(listOf<SearchCommand>(SearchCommand.QueueSong(TrackId("content://media/2"))), search.sent)
        assertEquals(0, closed)
    }

    @Test
    fun `playing a song plays it and leaves with the field emptied`() {
        show()

        compose.onNodeWithText("Night Drive").performClick()

        assertEquals(listOf(SearchCommand.PlaySong(TrackId("content://media/1")), SearchCommand.Clear), search.sent)
        assertEquals(1, closed)
    }

    @Test
    fun `playing a playlist and applying a preset leave too`() {
        show()

        scrollTo("Late night")
        compose.onNodeWithText("Late night").performClick()
        scrollTo("Night drive")
        compose.onNodeWithText("Night drive").performClick()

        assertEquals(
            listOf(
                SearchCommand.PlayPlaylist(PlaylistRef(1)),
                SearchCommand.Clear,
                SearchCommand.ApplyPreset(PresetId("Night drive")),
                SearchCommand.Clear,
            ),
            search.sent,
        )
        assertEquals(2, closed)
    }

    @Test
    fun `the clear button empties the field and the close button leaves`() {
        show()

        compose.onNodeWithContentDescription("Clear the search").performClick()
        compose.onNodeWithContentDescription("Close search").performClick()

        assertEquals(listOf(SearchCommand.Type(""), SearchCommand.Clear), search.sent)
        assertEquals(1, closed)
    }
}
