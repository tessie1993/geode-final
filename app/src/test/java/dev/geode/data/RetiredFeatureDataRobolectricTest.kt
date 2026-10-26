package dev.geode.data

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * An install upgraded from a build that still had the retired features starts clean: their files,
 * preference keys and notification channel are gone after one sweep, everything else is left as
 * it was, and the settings load as if the keys had never been written.
 *
 * Robolectric for real SharedPreferences, a real files directory and a real NotificationManager.
 * The plain Application keeps it away from GeodeApp, which loads the native engine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RetiredFeatureDataRobolectricTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    private val notifications: NotificationManager =
        context.getSystemService(NotificationManager::class.java)

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    private fun staleFileIn(dir: File) {
        dir.mkdirs()
        File(dir, "stale.json").writeText("{}")
    }

    private fun seedStaleData() {
        RetiredFeatureData.directories.forEach { staleFileIn(File(context.filesDir, it)) }
        RetiredFeatureData.cacheDirectories.forEach { staleFileIn(File(context.cacheDir, it)) }
        RetiredFeatureData.prefsKeys.forEach { (file, keys) ->
            val editor = prefs(file).edit()
            keys.forEach { editor.putBoolean(it, true) }
            editor.commit()
        }
        RetiredFeatureData.notificationChannels.forEach { id ->
            notifications.createNotificationChannel(
                NotificationChannel(id, id, NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    @Test
    fun `the sweep deletes every retired directory, preference key and channel`() {
        seedStaleData()

        RetiredFeatureData.sweepOnce(context)

        RetiredFeatureData.directories.forEach { dir ->
            assertFalse("$dir survived", File(context.filesDir, dir).exists())
        }
        RetiredFeatureData.cacheDirectories.forEach { dir ->
            assertFalse("cache/$dir survived", File(context.cacheDir, dir).exists())
        }
        RetiredFeatureData.prefsKeys.forEach { (file, keys) ->
            keys.forEach { key -> assertFalse("$file/$key survived", prefs(file).contains(key)) }
        }
        RetiredFeatureData.notificationChannels.forEach { id ->
            assertNull("channel $id survived", notifications.getNotificationChannel(id))
        }
    }

    @Test
    fun `the sweep leaves live data alone`() {
        seedStaleData()
        val presets = File(context.filesDir, "presets")
        presets.mkdirs()
        val keep = File(presets, "mine.json")
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
        val recreated = File(context.filesDir, RetiredFeatureData.directories.first())
        recreated.mkdirs()

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
