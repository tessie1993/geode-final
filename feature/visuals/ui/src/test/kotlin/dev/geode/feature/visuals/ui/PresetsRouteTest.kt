package dev.geode.feature.visuals.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import dev.geode.engine.types.PresetId
import dev.geode.feature.visuals.api.ImportProblem
import dev.geode.feature.visuals.api.PresetShare
import dev.geode.feature.visuals.api.Visuals
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsFact
import dev.geode.feature.visuals.api.VisualsState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Presets browser's route over a recording Visuals (#144): taps that change something are
 * Commands, the open folder and the note after an import stay in the route, and a preset ready to
 * share goes to the share sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PresetsRouteTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class RecordingVisuals(
        initial: VisualsState,
    ) : Visuals {
        override val state: StateFlow<VisualsState> = MutableStateFlow(initial)
        override val events = MutableSharedFlow<VisualsFact>(extraBufferCapacity = 8)
        val sent = mutableListOf<VisualsCommand>()

        override fun send(command: VisualsCommand) {
            sent += command
        }
    }

    private val visuals = RecordingVisuals(PreviewStates.ready)
    private val shared = mutableListOf<PresetShare>()
    private var backs = 0

    private fun open() = compose.setContent { PresetsRoute(visuals, onShare = { shared += it }, onBack = { backs++ }) }

    private fun scrollTo(text: String) = compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))

    private fun clipboard(text: String?) {
        val manager = compose.activity.getSystemService(ClipboardManager::class.java)
        if (text == null) manager.clearPrimaryClip() else manager.setPrimaryClip(ClipData.newPlainText("link", text))
    }

    @Test
    fun `opening lists the presets again, and a tap applies one`() {
        open()

        scrollTo("Late night drive")
        compose.onNodeWithText("Late night drive").performClick()
        scrollTo("Fluid Ink · Calm wash")
        compose.onNodeWithText("Fluid Ink · Calm wash").performClick()

        assertEquals(
            listOf(
                VisualsCommand.ReloadPresets,
                VisualsCommand.ApplyPreset(PresetId("Late night drive")),
                VisualsCommand.ApplyPreset(PresetId("Fluid Ink · Calm wash")),
            ),
            visuals.sent,
        )
    }

    @Test
    fun `folders open in the route, and Back climbs out of them`() {
        open()

        scrollTo("Calm")
        compose.onNodeWithText("Calm").performClick()
        compose.onNodeWithText("Rainy days").performClick()
        compose.onNodeWithText("Rain on glass").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Slow burn").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        assertEquals(listOf<VisualsCommand>(VisualsCommand.ReloadPresets), visuals.sent)
        assertEquals(1, backs)
    }

    @Test
    fun `the built-in presets open by Style`() {
        open()

        scrollTo("Built-in presets")
        compose.onNodeWithText("Built-in presets").performClick()
        compose.onNodeWithText("Bars").performClick()
        compose.onNodeWithText("Bars · Retro").assertExists()
    }

    @Test
    fun `a saved preset is shared from its menu, and handed to the share sheet when ready`() {
        open()

        scrollTo("Late night drive")
        compose.onNodeWithContentDescription("More for Late night drive").performClick()
        compose.onNodeWithText("Share").performClick()
        val link = PresetShare.Link(PresetId("Late night drive"), "geode://preset/abc")
        visuals.events.tryEmit(VisualsFact.PresetShareReady(link))
        compose.waitForIdle()

        assertEquals(VisualsCommand.SharePreset(PresetId("Late night drive")), visuals.sent.last())
        assertEquals(listOf<PresetShare>(link), shared)
    }

    @Test
    fun `a pasted link is imported and the outcome noted`() {
        open()
        clipboard("look: geode://preset/abc")

        compose.onNodeWithText("Paste a shared preset").performClick()
        visuals.events.tryEmit(VisualsFact.PresetImported(PresetId("Calm 2")))
        compose.onNodeWithText("Imported \"Calm 2\"").assertExists()
        compose.onNodeWithContentDescription("Dismiss").performClick()
        compose.onNodeWithText("Imported \"Calm 2\"").assertDoesNotExist()
        visuals.events.tryEmit(VisualsFact.PresetNotImported(ImportProblem.NOT_A_PRESET))
        compose.onNodeWithText("That is not a Geode preset").assertExists()

        assertEquals(VisualsCommand.ImportPresetLink("look: geode://preset/abc"), visuals.sent.last())
    }

    @Test
    fun `an empty clipboard is noted, and nothing is sent`() {
        open()
        clipboard(null)

        compose.onNodeWithText("Paste a shared preset").performClick()

        compose.onNodeWithText("The clipboard is empty").assertExists()
        assertEquals(listOf<VisualsCommand>(VisualsCommand.ReloadPresets), visuals.sent)
    }

    @Test
    fun `deleting asks first`() {
        open()

        scrollTo("Late night drive")
        compose.onNodeWithContentDescription("More for Late night drive").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete \"Late night drive\"?").assertExists()
        compose.onNodeWithText("Delete").performClick()

        assertEquals(VisualsCommand.DeletePreset(PresetId("Late night drive")), visuals.sent.last())
    }
}
