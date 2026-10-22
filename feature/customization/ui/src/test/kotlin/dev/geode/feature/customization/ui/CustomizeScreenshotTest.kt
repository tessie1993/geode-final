package dev.geode.feature.customization.ui

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

/** App UI screenshots of the Customize panel, taken from its previews (ADR-0003). Never the Visual. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], application = Application::class, qualifiers = "w411dp-h891dp")
class CustomizeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun basic() = capture("Customize_basic") { CustomizeBasicPreview() }

    @Test
    fun basicLight() = capture("Customize_basic_light") { CustomizeBasicLightPreview() }

    @Test
    fun fresh() = capture("Customize_fresh") { CustomizeFreshPreview() }

    @Test
    @Config(qualifiers = "w891dp-h411dp-land")
    fun landscape() = capture("Customize_landscape") { CustomizeLandscapePreview() }

    @Test
    fun reactions() = capture("Customize_reactions") { CustomizeReactionsPreview() }

    @Test
    fun board() = capture("Customize_board") { CustomizeBoardPreview() }

    @Test
    fun noLinks() = capture("Customize_no_links") { CustomizeNoLinksPreview() }

    @Test
    fun camera() = capture("Customize_camera") { CustomizeCameraPreview() }

    @Test
    fun effects() = capture("Customize_effects") { CustomizeEffectsPreview() }

    @Test
    fun extras() = capture("Customize_extras") { CustomizeExtrasPreview() }

    @Test
    fun macro() = capture("Customize_macro") { CustomizeMacroPreview() }

    @Test
    fun chooseSource() = capture("Customize_choose_source") { CustomizeChooseSourcePreview() }

    @Test
    fun chooseTarget() = capture("Customize_choose_target") { CustomizeChooseTargetPreview() }

    @Test
    fun reactionEditor() = capture("Customize_reaction_editor") { CustomizeReactionEditorPreview() }

    @Test
    fun savePreset() = capture("Customize_save_preset") { CustomizeSavePresetPreview() }

    private fun capture(
        name: String,
        content: @Composable () -> Unit,
    ) {
        compose.setContent(content)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }
}
