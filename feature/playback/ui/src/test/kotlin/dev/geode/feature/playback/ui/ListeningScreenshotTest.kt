package dev.geode.feature.playback.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** App UI screenshots of the queue and the listening tools (#35), taken from their previews (ADR-0003). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class ListeningScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun queue() = capture("QueueScreen_shuffled") { QueueShuffledPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun queueDark() = capture("QueueScreen_dark") { QueueDarkPreview() }

    @Test
    fun queueEmpty() = capture("QueueScreen_empty") { QueueEmptyPreview() }

    @Test
    fun tools() = capture("ListeningScreen_default") { ListeningToolsPreview() }

    @Test
    fun toolsInUse() = capture("ListeningScreen_in_use") { ListeningToolsInUsePreview() }

    @Test
    @Config(qualifiers = "+night")
    fun toolsDark() = capture("ListeningScreen_after_track_dark") { ListeningToolsDarkPreview() }

    private fun capture(
        name: String,
        content: @Composable () -> Unit,
    ) {
        compose.setContent(content)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}
