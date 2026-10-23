package dev.geode.feature.playback.ui

import android.app.Application
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import dev.geode.engine.types.Millis
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Dragging the seek bar (#147) shows the time it would seek to under it, as the old Slider did, and
 * seeks there only once it is let go.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SeekBarDragRobolectricTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<PlayerAction>()

    @Test
    fun `while dragged to the start the time shown is the start, and letting go seeks there`() {
        // The preview track is 3:35 long and is playing at 1:04.
        compose.setContent { PlayerScreen(PreviewModels.playing, onAction = { sent += it }) }
        compose.onNodeWithText("1:04").assertExists()

        compose.onNodeWithContentDescription("Position in the song").performTouchInput {
            down(center)
            repeat(DRAG_STEPS) { moveBy(Offset(-width.toFloat() / DRAG_STEPS, 0f)) }
        }

        compose.onNodeWithText("0:00").assertExists()
        compose.onNodeWithText("1:04").assertDoesNotExist()
        assertEquals(emptyList<PlayerAction>(), sent.filterIsInstance<PlayerAction.SeekTo>())

        compose.onNodeWithContentDescription("Position in the song").performTouchInput { up() }

        compose.onNodeWithText("1:04").assertExists()
        assertEquals(listOf(PlayerAction.SeekTo(Millis.ZERO)), sent.filterIsInstance<PlayerAction.SeekTo>())
    }

    private companion object {
        const val DRAG_STEPS = 10
    }
}
