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

/** App UI screenshots of the Visuals tab, full-screen visual mode and the Presets browser, taken from their previews (ADR-0003). */
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
    fun presets() = capture("Presets") { PresetsTopPreview() }

    @Test
    fun presetsLight() = capture("Presets_light") { PresetsLightPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun presetsLoading() = capture("Presets_loading") { PresetsLoadingPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun presetsFolder() = capture("Presets_folder") { PresetsFolderPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun presetsBuiltIn() = capture("Presets_built_in") { PresetsBuiltInPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun presetsBuiltInStyle() = capture("Presets_built_in_style") { PresetsBuiltInStylePreview() }

    @Test
    @Config(qualifiers = "+night")
    fun presetsImported() = capture("Presets_imported") { PresetsImportedPreview() }

    @Test
    @Config(qualifiers = "+night")
    fun presetsNoneSaved() = capture("Presets_none_saved") { PresetsNoneSavedPreview() }

    private fun capture(
        name: String,
        content: @Composable () -> Unit,
    ) {
        compose.setContent(content)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}
