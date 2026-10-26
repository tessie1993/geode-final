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

/** Start over (#400) sits beside the look picker, and only where the opening place can carry it out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class StartOverTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `Start over sends its action`() {
        val actions = mutableListOf<CustomizeAction>()
        compose.setContent { CustomizeScreen(PreviewModels.editing.copy(canStartOver = true), onAction = { actions += it }) }

        compose.onNodeWithText("Start over").performScrollTo().performClick()

        assertEquals(listOf<CustomizeAction>(CustomizeAction.StartOver), actions)
    }

    @Test
    fun `without the engine there is no Start over`() {
        compose.setContent { CustomizeScreen(PreviewModels.editing, onAction = {}) }

        compose.onNodeWithText("Start over").assertDoesNotExist()
    }
}
