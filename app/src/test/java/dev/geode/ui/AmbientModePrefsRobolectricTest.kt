package dev.geode.ui

import android.app.Application
import android.content.Context
import dev.geode.design.ambient.AmbientMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The floating assets' mode (issue #72) is kept with the other App UI preferences.
 *
 * Robolectric for real SharedPreferences. The plain Application keeps it away from GeodeApp, which
 * loads the native engine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AmbientModePrefsRobolectricTest {
    private val prefs =
        RuntimeEnvironment
            .getApplication()
            .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val store = ThemeStore(prefs)

    @Test
    fun `a fresh install drifts`() {
        assertEquals(AmbientMode.Drifting, store.loadGui().ambientMode)
    }

    @Test
    fun `every mode survives a save and a load`() {
        for (mode in AmbientMode.entries) {
            store.saveGui(GuiPrefs(ambientMode = mode))
            assertEquals(mode, store.loadGui().ambientMode)
        }
    }

    @Test
    fun `saving the mode leaves the other preferences as they were`() {
        val saved = GuiPrefs(ambientMode = AmbientMode.Off, compactPlayer = true, reducedMotion = true)
        store.saveGui(saved)
        assertEquals(saved, store.loadGui())
    }

    @Test
    fun `an unknown stored mode falls back to drifting`() {
        prefs.edit().putString(ThemeStore.KEY_AMBIENT_MODE, "Swirling").commit()
        assertEquals(AmbientMode.Drifting, store.loadGui().ambientMode)
    }

    private companion object {
        const val PREFS_FILE = "ambient_mode_prefs_test"
    }
}
