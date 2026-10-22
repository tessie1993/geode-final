package dev.geode.ui

import dev.geode.audio.AudioFxState
import dev.geode.data.PlayerPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The old screens' settings, read from and saved to the Settings store (#17, Settings slice). What
 * the engine does with them is [dev.geode.audio.EngineTuning]'s job; this only keeps the Visual's
 * cached beats in step when the beat detection changes.
 */
internal class PlayerSettingsController(
    private val settings: SettingsForOldScreens,
    private val scope: CoroutineScope,
    private val host: Host,
) {
    interface Host {
        fun redecideCachedBeats(prefs: GuiPrefs)
    }

    val guiPrefs: StateFlow<GuiPrefs> = settings.guiPrefs

    val playerPrefs: StateFlow<PlayerPrefs> = settings.prefs

    val audioFxState: StateFlow<AudioFxState> = settings.audioFx

    fun setGuiPrefs(prefs: GuiPrefs) {
        val previous = guiPrefs.value
        scope.launch { settings.setGuiPrefs(prefs) }
        val sensitivityChanged =
            previous.beatSensitivity != prefs.beatSensitivity ||
                previous.effectiveBeatMinIntervalMs != prefs.effectiveBeatMinIntervalMs
        if (sensitivityChanged) host.redecideCachedBeats(prefs)
    }

    fun setPlayerPrefs(prefs: PlayerPrefs) = settings.setPlayerPrefs(prefs)

    fun setAudioFxEnabled(enabled: Boolean) = settings.setEqualizerEnabled(enabled)

    fun setAudioFxBand(
        band: Int,
        levelMb: Int,
    ) = settings.setEqualizerBand(band, levelMb)

    fun useAudioFxPreset(index: Int) = settings.useEqualizerPreset(index)

    fun setAudioFxBassBoost(strength: Int) = settings.setBassBoost(strength)

    fun setAudioFxLoudness(gainMb: Int) = settings.setLoudness(gainMb)
}
