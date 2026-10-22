package dev.geode.feature.home.ui

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

/** App UI screenshots of Home and the bar in each first-wave Element, taken from their previews (ADR-0003). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w411dp-h891dp")
class HomeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun homeWater() = capture("Home_Water") { HomeWaterPreview() }

    @Test
    fun homeWood() = capture("Home_Wood") { HomeWoodPreview() }

    @Test
    fun homeStone() = capture("Home_Stone") { HomeStonePreview() }

    @Test
    fun homeBioluminescence() = capture("Home_Bioluminescence") { HomeBioluminescencePreview() }

    @Test
    fun barWater() = capture("PlaceBar_Water") { PlaceBarWaterPreview() }

    @Test
    fun barWood() = capture("PlaceBar_Wood") { PlaceBarWoodPreview() }

    @Test
    fun barStone() = capture("PlaceBar_Stone") { PlaceBarStonePreview() }

    @Test
    fun barBioluminescence() = capture("PlaceBar_Bioluminescence") { PlaceBarBioluminescencePreview() }

    private fun capture(
        name: String,
        preview: @Composable () -> Unit,
    ) {
        compose.setContent { preview() }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}
