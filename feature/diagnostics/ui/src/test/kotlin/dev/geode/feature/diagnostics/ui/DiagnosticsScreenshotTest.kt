package dev.geode.feature.diagnostics.ui

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

/** App UI screenshots of Diagnostics' screens, taken from their previews (ADR-0003). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class DiagnosticsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun ready() = capture("Diagnostics_ready") { DiagnosticsReadyPreview() }

    @Test
    fun preparing() = capture("Diagnostics_preparing") { DiagnosticsPreparingPreview() }

    @Test
    fun failed() = capture("Diagnostics_failed") { DiagnosticsFailedPreview() }

    @Test
    fun developer() = capture("Diagnostics_developer") { DiagnosticsDeveloperPreview() }

    @Test
    fun overlay() = capture("DeveloperOverlay_stats") { DeveloperOverlayPreview() }

    @Test
    fun overlayWaiting() = capture("DeveloperOverlay_waiting") { DeveloperOverlayWaitingPreview() }

    private fun capture(
        name: String,
        content: @Composable () -> Unit,
    ) {
        compose.setContent(content)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}
