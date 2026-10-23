package dev.geode.ui

import dev.geode.data.PlayerPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The old screens' settings, read from and saved to the Settings store (#17, Settings slice). What
 * the engine does with them, the live beat detection among them, is [dev.geode.audio.EngineTuning]'s job.
 */
internal class PlayerSettingsController(
    private val settings: SettingsForOldScreens,
    private val scope: CoroutineScope,
) {
    val guiPrefs: StateFlow<GuiPrefs> = settings.guiPrefs

    val playerPrefs: StateFlow<PlayerPrefs> = settings.prefs

    fun setGuiPrefs(prefs: GuiPrefs) {
        scope.launch { settings.setGuiPrefs(prefs) }
    }

    fun setPlayerPrefs(prefs: PlayerPrefs) = settings.setPlayerPrefs(prefs)
}
