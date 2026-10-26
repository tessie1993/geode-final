package dev.geode.feature.settings.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsCommand
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.settings.api.SettingsValues
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings the classic way (#355): it opens on the list of its sections, a tap opens one as a page of
 * its own, and the page's back button or system Back returns to the list. Which section is open is
 * App-UI state, so it survives a rotation or the process coming back, and opening one sends nothing to
 * Settings.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SettingsRouteTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private class RecordingSettings : Settings {
        override val state: StateFlow<SettingsState> = MutableStateFlow(SettingsState.Ready(SettingsValues()))
        override val events: Flow<Nothing> = emptyFlow()
        val sent = mutableListOf<SettingsCommand>()

        override fun send(command: SettingsCommand) {
            sent += command
        }
    }

    private val settings = RecordingSettings()

    private fun route() =
        SettingsRoute(settings, fontColors = previewFontColors, appVersion = "1.8.0", onStartTutorial = {}, onSetLiveWallpaper = {})

    private fun assertOnList() {
        compose.onNodeWithText("Settings").assertExists()
        compose.onNodeWithText("Default paths").assertExists()
        compose.onNodeWithText("Where videos, presets and music are kept").assertExists()
        compose.onNodeWithContentDescription("Back to Settings").assertDoesNotExist()
    }

    private fun assertOnAudio() {
        compose.onNodeWithText("Audio").assertExists()
        compose.onNodeWithContentDescription("Back to Settings").assertExists()
        compose.onNodeWithText("Default paths").assertDoesNotExist()
    }

    @Test
    fun `it opens on the list, a section opens as its own page, and its back button returns`() {
        compose.setContent { route() }
        assertOnList()

        compose.onNodeWithText("Audio").performClick()
        assertOnAudio()

        compose.onNodeWithContentDescription("Back to Settings").performClick()
        assertOnList()
        assertEquals(emptyList<SettingsCommand>(), settings.sent)
    }

    @Test
    fun `system Back leaves a section for the list`() {
        compose.setContent { route() }
        compose.onNodeWithText("About").performClick()
        compose.onNodeWithText("Version 1.8.0").assertExists()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        assertOnList()
    }

    @Test
    fun `the open section survives the activity being recreated`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { route() }
        compose.onNodeWithText("Audio").performClick()

        restoration.emulateSavedInstanceStateRestore()

        assertOnAudio()
    }
}
