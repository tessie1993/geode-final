package dev.geode.feature.customization.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The way into the Presets browser (#144) sits beside this Style's preset chips. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class BrowsePresetsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `All presets opens the Presets browser`() {
        val actions = mutableListOf<CustomizeAction>()
        compose.setContent { CustomizeScreen(PreviewModels.editing, onAction = { actions += it }) }

        compose.onNodeWithText("All presets").performScrollTo().performClick()

        assertEquals(listOf<CustomizeAction>(CustomizeAction.BrowsePresets), actions)
    }
}
