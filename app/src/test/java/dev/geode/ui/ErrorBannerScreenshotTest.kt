package dev.geode.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * App UI screenshot of the app-wide error banner (#147, ADR-0004), in light and dark. The plain
 * Application keeps Robolectric away from GeodeApp, which loads the native engine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class ErrorBannerScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun light() = capture(dark = false)

    @Test
    @Config(qualifiers = "+night")
    fun dark() = capture(dark = true)

    private fun capture(dark: Boolean) {
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface {
                    ErrorBanner(ErrorBannerModel("Could not save the playlist", counting = true), onAction = {})
                }
            }
        }
        val mode = if (dark) "dark" else "light"
        compose.onRoot().captureRoboImage("src/test/screenshots/ErrorBanner_$mode.png")
    }
}
