package dev.geode.ui.shell

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The app-wide error banner (#147, ADR-0004): one failure on show at a time, and dismissing it
 * clears it. The host is the same shape as `AppRoot`: the App UI store's banner, if any, drawn once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AppFailureBannerRobolectricTest {
    @get:Rule
    val compose = createComposeRule()

    private val runtime = inPlaceRuntime()
    private val appUi = appUiStore(runtime)

    @Test
    fun `one failure shows once and dismissing it clears the banner`() {
        compose.setContent {
            MaterialTheme {
                val state by appUi.state.collectAsState()
                state.banner?.let { banner ->
                    AppFailureBanner(banner, onDismiss = { appUi.send(AppUiCommand.DismissBanner) })
                }
            }
        }

        runtime.errors.report(FeatureFailure("Library", "scan failed"))

        compose.onAllNodesWithText("scan failed").assertCountEquals(1)
        compose.onNodeWithContentDescription("Dismiss message").performClick()
        compose.onAllNodesWithText("scan failed").assertCountEquals(0)
        assertNull(appUi.state.value.banner)
    }

    @Test
    fun `a newer failure replaces the one on show instead of stacking`() {
        compose.setContent {
            MaterialTheme {
                val state by appUi.state.collectAsState()
                state.banner?.let { banner ->
                    AppFailureBanner(banner, onDismiss = { appUi.send(AppUiCommand.DismissBanner) })
                }
            }
        }

        runtime.errors.report(FeatureFailure("Library", "scan failed"))
        runtime.errors.report(FeatureFailure("Playback", "decode error"))

        compose.onAllNodesWithText("scan failed").assertCountEquals(0)
        compose.onAllNodesWithText("decode error").assertCountEquals(1)
    }
}
