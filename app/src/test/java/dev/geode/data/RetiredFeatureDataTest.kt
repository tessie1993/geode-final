package dev.geode.data

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * An install upgraded from a build that still had the retired features starts clean: their files
 * and preference keys are gone after one sweep, and everything else is left as it was.
 *
 * Robolectric for real SharedPreferences and a real files directory. The plain Application keeps
 * it away from GeodeApp, which loads the native engine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RetiredFeatureDataTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun seedStaleData() {
        RetiredFeatureData.directories.forEach { dir ->
            File(context.filesDir, dir).apply { mkdirs() }.resolve("stale.json").writeText("{}")
        }
        RetiredFeatureData.prefsKeys.forEach { (file, keys) ->
            prefs(file).edit().apply { keys.forEach { putString(it, "stale") } }.commit()
        }
    }

    @Test
    fun `the sweep deletes every retired directory and preference key`() {
        seedStaleData()

        RetiredFeatureData.sweepOnce(context)

        RetiredFeatureData.directories.forEach { dir ->
            assertFalse("$dir survived", File(context.filesDir, dir).exists())
        }
        RetiredFeatureData.prefsKeys.forEach { (file, keys) ->
            keys.forEach { key -> assertFalse("$file/$key survived", prefs(file).contains(key)) }
        }
    }

    @Test
    fun `the sweep leaves live data alone`() {
        seedStaleData()
        val keep = File(context.filesDir, "presets").apply { mkdirs() }.resolve("mine.json")
        keep.writeText("{}")
        prefs(GeodePrefsFiles.GENERAL).edit().putString("gui_player_pos", "TOP").commit()

        RetiredFeatureData.sweepOnce(context)

        assertTrue(keep.exists())
        assertEquals("TOP", prefs(GeodePrefsFiles.GENERAL).getString("gui_player_pos", null))
    }

    @Test
    fun `the sweep runs once per install`() {
        RetiredFeatureData.sweepOnce(context)
        assertTrue(prefs(GeodePrefsFiles.GENERAL).getBoolean(RetiredFeatureData.SWEPT_KEY, false))
        val recreated = File(context.filesDir, RetiredFeatureData.directories.first()).apply { mkdirs() }

        RetiredFeatureData.sweepOnce(context)

        assertTrue(recreated.exists())
    }

    @Test
    fun `a fresh install sweeps without failing`() {
        RetiredFeatureData.sweepOnce(context)

        RetiredFeatureData.directories.forEach { dir ->
            assertFalse(File(context.filesDir, dir).exists())
        }
    }
}
