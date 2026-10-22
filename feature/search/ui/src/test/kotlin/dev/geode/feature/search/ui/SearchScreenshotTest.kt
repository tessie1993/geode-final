package dev.geode.feature.search.ui

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

/** App UI screenshots of global Search, taken from its previews (ADR-0003). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class SearchScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "+night")
    fun empty() = capture("Search_empty") { SearchEmptyPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun found() = capture("Search_found") { SearchFoundPreview() }

    @Test
    fun foundLight() = capture("Search_found_light") { SearchFoundLightPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun presetsOnly() = capture("Search_presets_only") { SearchPresetsOnlyPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun nothingFound() = capture("Search_nothing_found") { SearchNothingFoundPreview() }

    private fun capture(
        name: String,
        content: @Composable () -> Unit,
    ) {
        compose.setContent(content)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}
