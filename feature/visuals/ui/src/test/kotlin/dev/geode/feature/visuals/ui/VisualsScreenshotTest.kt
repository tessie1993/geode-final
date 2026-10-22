package dev.geode.feature.visuals.ui

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

/** App UI screenshots of the Visuals tab, full-screen visual mode and the MilkDrop screen, taken from their previews (ADR-0003). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class)
class VisualsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = "+night")
    fun tab() = capture("VisualsTab") { VisualsTabPreview() }

    @Test
    fun tabLight() = capture("VisualsTab_light") { VisualsTabLightPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun tabLoading() = capture("VisualsTab_loading") { VisualsTabLoadingPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun tabFolder() = capture("VisualsTab_folder") { VisualsTabFolderPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun tabSearch() = capture("VisualsTab_search") { VisualsTabSearchPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun tabNothingFound() = capture("VisualsTab_nothing_found") { VisualsTabNothingFoundPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun fullScreenControls() = capture("FullScreenVisual_controls") { FullScreenControlsPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun fullScreenHidden() = capture("FullScreenVisual_hidden") { FullScreenHiddenPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun milkdrop() = capture("Milkdrop") { MilkdropPreview() }

    @Test
    fun milkdropLight() = capture("Milkdrop_light") { MilkdropLightPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun milkdropLoading() = capture("Milkdrop_loading") { MilkdropLoadingPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun milkdropImporting() = capture("Milkdrop_importing") { MilkdropImportingPreview() }

    private fun capture(
        name: String,
        content: @Composable () -> Unit,
    ) {
        compose.setContent(content)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}
