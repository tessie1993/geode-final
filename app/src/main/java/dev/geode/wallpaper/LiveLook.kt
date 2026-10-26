package dev.geode.wallpaper

import android.content.Context
import android.content.SharedPreferences
import dev.geode.data.GeodePrefsFiles
import dev.geode.data.PresetStore
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.render.VisualizerRenderer

/**
 * The look the app was last left showing, as the live wallpaper and the screen saver (#406) both draw
 * it while the app is closed: its Style and parameters, its MilkDrop preset, and reduced motion.
 */
internal object LiveLook {
    /** Puts the last look on [engine]; anything missing or unreadable leaves the engine's default. */
    fun restoreInto(
        context: Context,
        engine: VisualizerRenderer,
        settings: Settings,
    ) {
        val prefsFiles = GeodePrefsFiles(context)
        val prefs = prefsFiles.viz
        prefs.getString("live_state", null)?.let { json ->
            runCatching { PresetStore.fromJson(json) }.getOrNull()?.let { preset ->
                engine.requestedSceneId = preset.sceneId
                engine.sceneParams = preset.params
            }
        }
        prefs.getString("milk_path", null)?.let { path ->
            if (java.io.File(path).isFile) engine.loadMilkPreset(path)
        }
        // Settings, once read; before then, what the old settings file said.
        engine.reducedMotion =
            (settings.state.value as? SettingsState.Ready)?.values?.behavior?.reducedMotion
                ?: legacyReducedMotion(prefsFiles.general)
    }

    /**
     * Reduced motion as the old settings file kept it: its own switch, or the old first-run
     * question's answer that asked for it.
     */
    private fun legacyReducedMotion(prefs: SharedPreferences): Boolean =
        prefs.getBoolean("gui_reduced_motion", false) || prefs.getString("gui_safety_choice", null) == "REDUCED_MOTION"
}
