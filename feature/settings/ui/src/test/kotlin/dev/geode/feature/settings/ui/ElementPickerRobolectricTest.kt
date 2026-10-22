package dev.geode.feature.settings.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import dev.geode.design.element.ElementSkins
import dev.geode.design.element.ElementTheme
import dev.geode.feature.settings.api.Element
import dev.geode.feature.settings.api.LookCommand
import dev.geode.feature.settings.api.LookSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Element picker in Settings > Theme as a person uses it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w411dp-h891dp")
class ElementPickerRobolectricTest {
    @get:Rule
    val compose = createComposeRule()

    private val names = listOf("Water", "Wood", "Stone", "Bioluminescence")
    private val isChoice =
        SemanticsMatcher("is a radio choice") { it.config.getOrNull(SemanticsProperties.Role) == Role.RadioButton }

    @Test
    fun `the picker lists the four first-wave Elements, with the chosen one selected`() {
        compose.setContent { ElementPicker(selected = Element.STONE, onSelect = {}) }

        val shown =
            compose.onAllNodes(isChoice).fetchSemanticsNodes().map { node ->
                node.config[SemanticsProperties.Text].joinToString {
                    it.text
                }
            }
        assertEquals(names, shown)
        compose.onNode(isChoice and hasText("Stone")).assertIsSelected()
        for (other in names - "Stone") compose.onNode(isChoice and hasText(other)).assertIsNotSelected()
    }

    @Test
    fun `every Element is a tap target of at least 48 dp`() {
        compose.setContent { ElementPicker(selected = Element.WATER, onSelect = {}) }

        for (name in names) {
            compose.onNode(isChoice and hasText(name)).assertWidthIsAtLeast(TAP_TARGET).assertHeightIsAtLeast(TAP_TARGET)
        }
    }

    @Test
    fun `picking an Element in Settings changes the Element setting`() {
        val actions = mutableListOf<SettingsAction>()
        compose.setContent {
            SettingsScreen(previewModel(SettingsSection.THEME), onAction = { actions += it })
        }

        compose.onNode(isChoice and hasText("Bioluminescence")).performClick()
        compose.onNode(isChoice and hasText("Wood")).performClick()

        assertEquals(
            listOf(
                SettingsAction.Change(LookCommand.SetElement(Element.BIOLUMINESCENCE)),
                SettingsAction.Change(LookCommand.SetElement(Element.WOOD)),
            ),
            actions,
        )
    }

    @Test
    fun `the whole screen switches to a newly chosen Element at once`() {
        var element by mutableStateOf(Element.WATER)
        var ground = Color.Unspecified
        compose.setContent {
            ElementTheme(element.tokens()) {
                ground = MaterialTheme.colorScheme.background
                SettingsScreen(
                    previewModel(SettingsSection.THEME, previewValues.copy(look = LookSettings(element = element))),
                    onAction = {},
                )
            }
        }

        val grounds = mutableListOf<Color>()
        for (next in Element.entries) {
            element = next
            compose.waitForIdle()
            grounds += ground
            compose.onNode(isChoice and hasText(names[next.ordinal])).assertIsSelected()
        }
        assertEquals(listOf(Color(0xFF071A1C), Color(0xFF0D1A14), Color(0xFF18120E), Color(0xFF060D0B)), grounds)
    }

    @Test
    fun `each Element meets the look :design gives it`() {
        assertSame(ElementSkins.Water, Element.WATER.tokens())
        assertSame(ElementSkins.Wood, Element.WOOD.tokens())
        assertSame(ElementSkins.Stone, Element.STONE.tokens())
        assertSame(ElementSkins.Bioluminescence, Element.BIOLUMINESCENCE.tokens())
    }

    private companion object {
        val TAP_TARGET = 48.dp
    }
}
