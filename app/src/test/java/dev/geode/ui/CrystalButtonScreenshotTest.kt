package dev.geode.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.geode.design.CrystalButton
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * App UI screenshot of the three button states, in light and dark. It proves the screenshot
 * pipeline end to end; feature screens add their own as they are rebuilt (ADR-0003).
 *
 * The plain Application keeps Robolectric away from GeodeApp, which loads the native engine.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class CrystalButtonScreenshotTest {
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
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CrystalButton(onClick = {}) { Text("Play") }
                        CrystalButton(onClick = {}, filled = false) { Text("Queue") }
                        CrystalButton(onClick = {}, enabled = false) { Text("Disabled") }
                        CrystalButton(onClick = {}, compact = true) { Text("Compact") }
                    }
                }
            }
        }
        val mode = if (dark) "dark" else "light"
        compose.onRoot().captureRoboImage("src/test/screenshots/CrystalButton_$mode.png")
    }
}
