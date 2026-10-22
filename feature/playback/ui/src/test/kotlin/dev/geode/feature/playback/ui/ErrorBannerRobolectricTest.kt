package dev.geode.feature.playback.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app-wide error banner (#147) says when its message is on screen, and is dismissed with a tap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ErrorBannerRobolectricTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<BannerAction>()

    @Test
    fun `a message that waited is shown and says so once`() {
        compose.setContent { ErrorBanner(BannerModel("Could not read the music", counting = false), onAction = { sent += it }) }

        compose.onNodeWithText("Could not read the music").assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(listOf<BannerAction>(BannerAction.Shown), sent)
    }

    @Test
    fun `a message already counting does not say so again, and Dismiss dismisses it`() {
        compose.setContent { ErrorBanner(BannerModel("Could not read the music", counting = true), onAction = { sent += it }) }

        compose.onNodeWithText("Dismiss").performClick()

        assertEquals(listOf<BannerAction>(BannerAction.Dismiss), sent)
    }

    @Test
    fun `with no message there is no banner`() {
        compose.setContent { ErrorBanner(null, onAction = { sent += it }) }

        compose.onNodeWithText("Dismiss").assertDoesNotExist()
        assertEquals(emptyList<BannerAction>(), sent)
    }
}
