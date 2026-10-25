package dev.geode.feature.home.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.geode.design.element.ElementNavBarDefaults
import dev.geode.design.element.ElementSkins
import dev.geode.design.element.ElementTheme
import dev.geode.feature.home.api.HomeAction
import dev.geode.feature.home.api.Place
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The bar and Home as a person uses them: what they show, what a tap opens, and their tap targets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "w411dp-h891dp")
class HomeRobolectricTest {
    @get:Rule
    val compose = createComposeRule()

    private val barLabels = listOf("Home", "Player", "Library", "Visuals", "Studio")
    private val isTab = SemanticsMatcher("is a tab") { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }

    @Test
    fun `the bar shows its five places in the same order and with the same labels in every Element`() {
        var element by mutableStateOf(ElementSkins.Water)
        compose.setContent { ElementTheme(element) { PlaceBar(current = Place.HOME, onOpen = {}) } }

        for (next in ElementSkins.all) {
            element = next
            compose.waitForIdle()
            val tabs = compose.onAllNodes(isTab).fetchSemanticsNodes()
            val labels = tabs.map { node -> node.config[SemanticsProperties.Text].joinToString { it.text } }
            assertEquals(next.key, barLabels, labels)
            val lefts = tabs.map { it.boundsInRoot.left }
            assertEquals(next.key, lefts.sorted(), lefts)
        }
    }

    @Test
    fun `every place on the bar is at least 48 dp each way`() {
        var element by mutableStateOf(ElementSkins.Water)
        compose.setContent { ElementTheme(element) { PlaceBar(current = Place.HOME, onOpen = {}) } }

        for (next in ElementSkins.all) {
            element = next
            compose.waitForIdle()
            for (label in barLabels) {
                compose
                    .onNode(isTab and hasLabel(label))
                    .assertWidthIsAtLeast(ElementNavBarDefaults.TapTarget)
                    .assertHeightIsAtLeast(ElementNavBarDefaults.TapTarget)
            }
        }
    }

    @Test
    fun `the current place is the selected one, and none is on Settings`() {
        var current by mutableStateOf(Place.LIBRARY)
        compose.setContent { ElementTheme(ElementSkins.Water) { PlaceBar(current = current, onOpen = {}) } }

        compose.onNode(isTab and hasLabel("Library")).assertIsSelected()
        compose.onNode(isTab and hasLabel("Home")).assertIsNotSelected()

        current = Place.SETTINGS
        compose.waitForIdle()
        for (label in barLabels) compose.onNode(isTab and hasLabel(label)).assertIsNotSelected()
    }

    @Test
    fun `a tap on a place opens it`() {
        val opened = mutableListOf<Place>()
        compose.setContent { ElementTheme(ElementSkins.Stone) { PlaceBar(current = Place.HOME, onOpen = { opened += it }) } }

        for (label in barLabels) compose.onNode(isTab and hasLabel(label)).performClick()

        assertEquals(Place.bar, opened)
    }

    @Test
    fun `Settings opens from Home`() {
        val actions = mutableListOf<HomeAction>()
        compose.setContent { ElementTheme(ElementSkins.Wood) { HomeScreen(HomeScreenModel(), onAction = { actions += it }) } }

        compose
            .onNodeWithText("Settings")
            .assertWidthIsAtLeast(ElementNavBarDefaults.TapTarget)
            .assertHeightIsAtLeast(ElementNavBarDefaults.TapTarget)
            .performClick()

        assertEquals(listOf<HomeAction>(HomeAction.Open(Place.SETTINGS)), actions)
    }

    @Test
    fun `Home offers a shortcut for each of its places, in order`() {
        val actions = mutableListOf<HomeAction>()
        val model = HomeScreenModel(shortcuts = listOf(Place.SETTINGS, Place.LIBRARY))
        compose.setContent { ElementTheme(ElementSkins.Water) { HomeScreen(model, onAction = { actions += it }) } }

        compose.onNodeWithText("Library").performClick()
        compose.onNodeWithText("Settings").performClick()

        assertEquals(listOf(HomeAction.Open(Place.LIBRARY), HomeAction.Open(Place.SETTINGS)), actions)
        assertEquals(1, compose.onAllNodesWithText("Library").fetchSemanticsNodes().size)
    }

    private fun hasLabel(label: String) =
        SemanticsMatcher("has the label $label") { node ->
            node.config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .any { it.text == label }
        }
}
