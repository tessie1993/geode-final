package dev.geode.feature.settings.ui

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

/** App UI screenshots of every Settings section, taken from their previews (ADR-0003). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w411dp-h891dp")
class SettingsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theme() = capture("theme") { SettingsThemePreview() }

    @Test
    fun themeHome() = capture("theme_home") { SettingsThemeHomePreview() }

    @Test
    fun application() = capture("application") { SettingsApplicationPreview() }

    @Test
    fun audio() = capture("audio") { SettingsAudioPreview() }

    @Test
    fun export() = capture("export") { SettingsExportPreview() }

    @Test
    fun defaultPaths() = capture("default_paths") { SettingsDefaultPathsPreview() }

    @Test
    fun help() = capture("help") { SettingsHelpPreview() }

    @Test
    fun about() = capture("about") { SettingsAboutPreview() }

    @Test
    fun loading() = capture("loading") { SettingsLoadingPreview() }

    private fun capture(
        name: String,
        preview: @Composable () -> Unit,
    ) {
        compose.setContent { preview() }
        compose.onRoot().captureRoboImage("src/test/screenshots/Settings_$name.png")
    }
}
