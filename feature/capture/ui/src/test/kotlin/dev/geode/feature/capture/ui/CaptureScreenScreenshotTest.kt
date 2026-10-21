package dev.geode.feature.capture.ui

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

/** Screenshots of the Capture screen, one per state, taken from its previews (ADR-0003). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [ROBOLECTRIC_SDK])
class CaptureScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun off() = capture("off") { CaptureOffPreview() }

    @Test
    fun askingAboutMusic() = capture("asking_about_music") { CaptureAskingAboutMusicPreview() }

    @Test
    fun awaitingPermission() = capture("awaiting_permission") { CaptureAwaitingPermissionPreview() }

    @Test
    fun starting() = capture("starting") { CaptureStartingPreview() }

    @Test
    fun capturing() = capture("capturing") { CaptureCapturingPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun capturingDark() = capture("capturing_dark") { CaptureCapturingDarkPreview() }

    @Test
    fun failed() = capture("failed") { CaptureFailedPreview() }

    private fun capture(
        name: String,
        preview: @Composable () -> Unit,
    ) {
        compose.setContent(preview)
        compose.onRoot().captureRoboImage("src/test/screenshots/Capture_$name.png")
    }
}

private const val ROBOLECTRIC_SDK = 36
