package dev.geode.feature.playback.handlers

import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.core.MediaControls
import dev.geode.feature.settings.api.BackgroundSettings
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState

/**
 * Settings' "Music keeps playing" (#28), carried out: when it is off, leaving the app or locking the
 * phone pauses the music. While it is on, the music goes on with controls in the notification (#36).
 *
 * `:app` calls [onAppLeft] when the app stops being in front of the person (its process lifecycle
 * reaching "stopped"), at the switch-over (#17). While the settings are still being read, the
 * default (keep playing) holds.
 */
class MusicInBackground(
    private val playback: Playback,
    private val settings: Settings,
) {
    fun onAppLeft() {
        val keepsPlaying = (settings.state.value as? SettingsState.Ready)?.values?.background?.music ?: BackgroundSettings().music
        MediaControls.commandOnLeavingApp(playback.state.value.player, keepsPlaying)?.let(playback::send)
    }
}
