package dev.geode.feature.customization.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import dev.geode.feature.customization.api.CustomizationCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Dragging the Customize panel down lowers it to a slim peek bar so the Visual shows (#412). The bar
 * keeps the control touched last, still live; a tap on the bar or Back brings the panel back, and
 * where it rests survives the activity being recreated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CustomizePeekTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val actions = mutableListOf<CustomizeAction>()

    private val sent: List<CustomizationCommand>
        get() = actions.filterIsInstance<CustomizeAction.Send>().map { it.command }

    private fun setScreen(restoration: StateRestorationTester? = null) {
        val content: @Composable () -> Unit = {
            CustomizeScreen(
                PreviewModels.editing,
                onAction = { actions += it },
                start = CustomizePage.Advanced(AdvancedSection.EFFECTS),
            )
        }
        if (restoration == null) compose.setContent(content) else restoration.setContent(content)
    }

    private fun lower() {
        compose.onNodeWithContentDescription(HANDLE).performTouchInput { swipeDown(startY = centerY, endY = centerY + DRAG) }
        compose.waitForIdle()
    }

    private fun assertExpanded() {
        compose.onNodeWithContentDescription(HANDLE).assertExists()
        compose.onNodeWithText(ADVANCED).assertExists()
        compose.onNodeWithContentDescription(SHOW).assertDoesNotExist()
    }

    private fun assertPeeking() {
        compose.onNodeWithContentDescription(SHOW).assertExists()
        compose.onNodeWithContentDescription(HANDLE).assertDoesNotExist()
        compose.onNodeWithText(ADVANCED).assertDoesNotExist()
    }

    @Test
    fun `a drag down peeks with the last control, which stays live, and a tap on the bar brings the panel back`() {
        setScreen()
        assertExpanded()
        compose.onAllNodesWithContentDescription(STEP_UP)[0].performClick()
        val touched = sent.last() as CustomizationCommand.SetShared

        lower()

        assertPeeking()
        // The bar holds that one control, and a step on it still reaches the Customization.
        compose.onAllNodesWithContentDescription(STEP_UP).assertCountEquals(1)
        compose.onNodeWithContentDescription(STEP_UP).performClick()
        val nudged = sent.last() as CustomizationCommand.SetShared
        assertEquals(touched.property, nudged.property)

        compose.onNodeWithContentDescription(SHOW).performClick()
        compose.waitForIdle()

        assertExpanded()
        assertTrue(actions.none { it == CustomizeAction.Hide })
    }

    @Test
    fun `until a control is touched the bar says the changes show on the Visual`() {
        setScreen()
        lower()

        assertPeeking()
        compose.onNodeWithText(HINT).assertExists()
    }

    @Test
    fun `Back from the peek bar brings the panel back`() {
        setScreen()
        lower()
        assertPeeking()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        assertExpanded()
    }

    @Test
    fun `the panel stays lowered when the activity is recreated`() {
        val restoration = StateRestorationTester(compose)
        setScreen(restoration)
        lower()

        restoration.emulateSavedInstanceStateRestore()

        assertPeeking()
    }

    private companion object {
        const val HANDLE = "Drag down to watch the Visual, up for more controls"
        const val SHOW = "Show Customize"
        const val ADVANCED = "Advanced"
        const val STEP_UP = "Step up"
        const val HINT = "Your changes show live on the Visual. Tap or drag up to bring Customize back."

        /** Far past the panel's height, so the drag reaches the peek bar whatever the screen. */
        const val DRAG = 3_000f
    }
}
