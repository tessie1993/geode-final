package dev.geode.data

import android.app.NotificationManager
import android.content.Context
import androidx.core.content.edit
import java.io.File

/**
 * Clears, once, what the features retired in #9 left behind on an existing install.
 *
 * Nothing reads these files, keys or channels any more, so leaving them would be harmless but
 * wasteful: the directories can hold megabytes, and a dead channel still lists in system settings. The sweep runs once per install, then records that it has.
 */
internal object RetiredFeatureData {
    /** Set in [GeodePrefsFiles.GENERAL] once the sweep has run. */
    const val SWEPT_KEY = "retired_features_swept_v1"

    /** Directories under `filesDir` that only retired features wrote. */
    val directories: List<String> =
        listOf(
            // Takes: recorded performances.
            "takes",
            // Video templates: saved and imported `geode://template` documents.
            "templates",
        )

    /** Directories under `cacheDir` that only retired features wrote. */
    val cacheDirectories: List<String> =
        listOf(
            // Video templates: copies staged for a share sheet.
            "share/templates",
        )

    /** Preference keys only retired features wrote, by preferences file. */
    val prefsKeys: Map<String, List<String>> =
        mapOf(
            GeodePrefsFiles.GENERAL to
                listOf(
                    // Second-screen output.
                    "gui_second_screen",
                    // Boot ripple intro.
                    "boot_anim",
                    // Key colours.
                    "gui_key_color",
                ),
            GeodePrefsFiles.PLAYER to
                listOf(
                    // Bit-perfect USB output.
                    "bit_perfect",
                ),
        )

    /** Notification channels only retired features created. */
    val notificationChannels: List<String> =
        listOf(
            // Capturing other apps' audio: the ongoing capture notification.
            "geode-capture",
        )

    fun sweepOnce(context: Context) {
        val general = context.getSharedPreferences(GeodePrefsFiles.GENERAL, Context.MODE_PRIVATE)
        if (general.getBoolean(SWEPT_KEY, false)) return
        directories.forEach { File(context.filesDir, it).deleteRecursively() }
        cacheDirectories.forEach { File(context.cacheDir, it).deleteRecursively() }
        prefsKeys.forEach { (file, keys) ->
            context.getSharedPreferences(file, Context.MODE_PRIVATE).edit { keys.forEach { remove(it) } }
        }
        context.getSystemService(NotificationManager::class.java)?.let { manager ->
            notificationChannels.forEach { manager.deleteNotificationChannel(it) }
        }
        general.edit { putBoolean(SWEPT_KEY, true) }
    }
}
