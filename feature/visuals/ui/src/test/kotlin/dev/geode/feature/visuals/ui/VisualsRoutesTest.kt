package dev.geode.feature.visuals.ui

import android.app.Application
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import dev.geode.engine.types.MilkdropStyles
import dev.geode.engine.types.SceneId
import dev.geode.feature.visuals.api.MilkdropKind
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The routes over a recording Visuals: each tap that changes something is one Command, and the
 * App-UI-only state (search, the open folder) stays in the route. Whether full-screen mode's controls
 * show is App-UI state too, owned by `:app` because the double tap that toggles it lands on the Visual.
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
    fun `full-screen mode steps through the Styles, starts one over, hides its controls and leaves`() {
        var exits = 0
        compose.setContent {
            var controlsShown by remember { mutableStateOf(true) }
            FullScreenVisualRoute(
                visuals,
                controlsShown = controlsShown,
                keepScreenOn = true,
                onToggleControls = { controlsShown = !controlsShown },
                onExit = { exits++ },
            )
        }

        compose.onNodeWithContentDescription("Next Style").performClick()
        compose.onNodeWithContentDescription("Previous Style").performClick()
        compose.onNodeWithContentDescription("Start over").performClick()
        compose.onNodeWithText("Fluid Ink").assertExists()
        compose.onNodeWithContentDescription("Leave full screen").performClick()
        // The overlay takes no touches (they belong to the Style), so the toggle is its accessibility action.
        compose.onNodeWithContentDescription("Show or hide the controls").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Fluid Ink").assertDoesNotExist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        assertEquals(
            listOf(VisualsCommand.Choose(SceneId("fluid_aurora")), VisualsCommand.Choose(SceneId("bars")), VisualsCommand.StartOver),
            visuals.sent,
        )
        assertEquals(2, exits)
        assertTrue(
            compose.activity.window.decorView
                .keepsScreenOn(),
        )
    }

    @Test
    fun `the MilkDrop place opens the system picker and dismisses the report`() {
        val milkdrop = RecordingVisuals(PreviewStates.milkdrop)
        compose.setContent { MilkdropRoute(milkdrop) }

        compose.onNodeWithText("Import textures").performClick()
        val picker = shadowOf(compose.activity).nextStartedActivityForResult.intent
        compose.onNodeWithText("OK").performClick()

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.action)
        assertTrue(picker.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertEquals(listOf("image/*", "application/octet-stream"), picker.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
        assertEquals(listOf<VisualsCommand>(VisualsCommand.DismissImportReport), milkdrop.sent)
    }

    @Test
    fun `the preset playing can be loaded again, and the person's own deleted once confirmed`() {
        val milkdrop = RecordingVisuals(PreviewStates.milkdrop.copy(current = MilkdropStyles.of("Beach at dusk.milk")))
        var reloads = 0
        compose.setContent { MilkdropRoute(milkdrop, onReload = { reloads++ }) }

        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasContentDescription("Delete Beach at dusk"))
        compose.onNodeWithContentDescription("Load Beach at dusk again from its file").performClick()
        compose.onNodeWithContentDescription("Delete Beach at dusk").performClick()
        compose.onNodeWithText("Delete").performClick()

        assertEquals(1, reloads)
        assertEquals(listOf<VisualsCommand>(VisualsCommand.DeleteMilkdrop(MilkdropKind.PRESETS, "Beach at dusk.milk")), milkdrop.sent)
    }

    @Test
    fun `a preset Geode ships has no delete, and without the engine there is no reload`() {
        compose.setContent { MilkdropRoute(RecordingVisuals(PreviewStates.milkdrop.copy(current = MilkdropStyles.of("aurora_veil.milk")))) }

        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Aurora Veil"))
        compose.onNodeWithContentDescription("Delete Aurora Veil").assertDoesNotExist()
        compose.onNodeWithContentDescription("Load Aurora Veil again from its file").assertDoesNotExist()
    }

    @Test
    fun `nothing can be imported before the MilkDrop folder is listed`() {
        compose.setContent { MilkdropRoute(RecordingVisuals(PreviewStates.milkdropLoading)) }

        compose.onNodeWithText("Import presets").assertIsNotEnabled()
        compose.onNodeWithText("Finding your MilkDrop presets…").assertExists()
    }

    private fun View.keepsScreenOn(): Boolean =
        keepScreenOn || (this is ViewGroup && (0 until childCount).any { getChildAt(it).keepsScreenOn() })
}
