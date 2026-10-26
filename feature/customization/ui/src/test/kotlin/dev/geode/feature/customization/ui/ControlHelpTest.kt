package dev.geode.feature.customization.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.Target
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every Customize control carries a one-line help (#393), shown by a tap on its name. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ControlHelpTest {
    @get:Rule
    val compose = createComposeRule()

    private val speed = SharedProperty.SPEED
    private val row = SliderRow(Target.Shared(speed), speed.default, speed.min, speed.max, speed.kind, linked = false)

    @Test
    fun `a tap on a slider's name shows its help, and another hides it`() {
        compose.setContent {
            ValueSlider(row, label = "Speed", onSet = {}, onPreview = {}, onReset = {}, onReactTo = {}, help = targetHelp(row.target))
        }
        val help = "How fast the Style moves by itself."
        compose.onNodeWithText(help).assertDoesNotExist()

        compose.onNodeWithText("Speed").performClick()
        compose.onNodeWithText(help).assertExists()

        compose.onNodeWithText("Speed").performClick()
        compose.onNodeWithText(help).assertDoesNotExist()
    }

    @Test
    fun `a held slider shows its help the same way`() {
        compose.setContent {
            HeldSlider(label = "Rate", value = 1f, range = 0f..2f, text = { "$it" }, onSet = {}, help = "How fast the Modulator runs.")
        }
        compose.onNodeWithText("How fast the Modulator runs.").assertDoesNotExist()

        compose.onNodeWithText("Rate").performClick()
        compose.onNodeWithText("How fast the Modulator runs.").assertExists()
    }
}
