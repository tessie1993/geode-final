package dev.geode.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.geode.kernel.FakeClock
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app-wide error banner (#147) says when its message is on screen, and is dismissed with a tap.
 * The plain Application keeps Robolectric away from GeodeApp, which loads the native engine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ErrorBannerRobolectricTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = mutableListOf<ErrorBannerAction>()

    @Test
    fun `a message that waited is shown and says so once`() {
        compose.setContent { ErrorBanner(ErrorBannerModel("Could not read the music", counting = false), onAction = { sent += it }) }

        compose.onNodeWithText("Could not read the music").assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(listOf<ErrorBannerAction>(ErrorBannerAction.Shown), sent)
    }

    @Test
    fun `a message already counting does not say so again, and Dismiss dismisses it`() {
        compose.setContent { ErrorBanner(ErrorBannerModel("Could not read the music", counting = true), onAction = { sent += it }) }

        compose.onNodeWithText("Dismiss").performClick()

        assertEquals(listOf<ErrorBannerAction>(ErrorBannerAction.Dismiss), sent)
    }

    @Test
    fun `with no message there is no banner`() {
        compose.setContent { ErrorBanner(null, onAction = { sent += it }) }

        compose.onNodeWithText("Dismiss").assertDoesNotExist()
        assertEquals(emptyList<ErrorBannerAction>(), sent)
    }

    @Test
    fun `a failure any feature reports is shown over the screen, counted from then, and dismissed`() {
        val clock = FakeClock()
        val runtime = inPlaceRuntime(clock)
        val banner = AppErrorBannerState(runtime)
        compose.setContent { AppErrorBanner(banner) }

        runtime.errors.report(FeatureFailure("library", "Could not read the music"))
        compose.onNodeWithText("Could not read the music").assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(ErrorBannerModel("Could not read the music", counting = true), banner.model.value)

        compose.onNodeWithText("Dismiss").performClick()
        compose.onNodeWithText("Could not read the music").assertDoesNotExist()
        assertNull(banner.model.value)
    }
}
