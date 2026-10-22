package dev.geode.feature.visuals.ui

import android.app.Application
import android.view.View
import android.view.ViewGroup
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
import dev.geode.engine.types.SceneId
import dev.geode.feature.visuals.api.Visuals
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsFact
import dev.geode.feature.visuals.api.VisualsState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The routes over a recording Visuals: each tap that changes something is one Command, and the
 * App-UI-only state (search, the open folder, whether the controls show) stays in the route.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class VisualsRoutesTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class RecordingVisuals(
        initial: VisualsState,
    ) : Visuals {
        override val state: StateFlow<VisualsState> = MutableStateFlow(initial)
        override val events: Flow<VisualsFact> = MutableSharedFlow()
        val sent = mutableListOf<VisualsCommand>()

        override fun send(command: VisualsCommand) {
            sent += command
        }
    }

    private val visuals = RecordingVisuals(PreviewStates.ready)

    private fun scrollTo(text: String) = compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))

    @Test
    fun `going full screen, choosing a Style and favouriting it`() {
        var fullScreen = 0
        compose.setContent { VisualsRoute(visuals, onEnterFullScreen = { fullScreen++ }) }

        compose.onNodeWithText("Full screen").performClick()
        scrollTo("Bars")
        compose.onNodeWithText("Bars").performClick()
        compose.onNodeWithContentDescription("Add Bars to Favourites").performClick()

        assertEquals(1, fullScreen)
        assertEquals(listOf(VisualsCommand.Choose(SceneId("bars")), VisualsCommand.SetFavourite(SceneId("bars"), true)), visuals.sent)
    }

    @Test
    fun `searching and opening folders stay in the route`() {
        compose.setContent { VisualsRoute(visuals, onEnterFullScreen = {}) }

        scrollTo("Calm evenings")
        compose.onNodeWithText("Calm evenings").performClick()
        compose.onNodeWithText("Deep blue").assertExists()
        compose.onNodeWithText("All Styles").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("chladni")
        compose.onNodeWithText("Chladni Sand").assertExists()
        compose.onNodeWithText("Tunnel").assertDoesNotExist()

        assertEquals(emptyList<VisualsCommand>(), visuals.sent)
    }

    @Test
    fun `full-screen mode steps through the Styles, hides its controls and leaves`() {
        var exits = 0
        compose.setContent { FullScreenVisualRoute(visuals, keepScreenOn = true, onExit = { exits++ }) }

        compose.onNodeWithContentDescription("Next Style").performClick()
        compose.onNodeWithContentDescription("Previous Style").performClick()
        compose.onNodeWithText("Fluid Ink").assertExists()
        compose.onNodeWithContentDescription("Leave full screen").performClick()
        compose.onNodeWithContentDescription("Show or hide the controls").performClick()
        compose.onNodeWithText("Fluid Ink").assertDoesNotExist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        assertEquals(listOf(VisualsCommand.Choose(SceneId("fluid_aurora")), VisualsCommand.Choose(SceneId("bars"))), visuals.sent)
        assertEquals(2, exits)
        assertTrue(compose.activity.window.decorView.keepsScreenOn())
    }

    private fun View.keepsScreenOn(): Boolean =
        keepScreenOn || (this is ViewGroup && (0 until childCount).any { getChildAt(it).keepsScreenOn() })
}
