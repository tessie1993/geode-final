package dev.geode.playback

import dev.geode.feature.playback.api.LoadReason
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.playback.api.PlayerState
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Settings store "resume playback when the app opens" (#188: Settings stores, Playback runs).
 * Playback restores the session where it was left off, paused; when that switch is on, it is told to
 * play on once the restore has settled. Only the restore at the start of the process counts.
 */
object ResumeFromSettings {
    fun follow(
        settings: Settings,
        playback: Playback,
        scope: CoroutineScope,
    ) {
        scope.launch {
            var restoring = false
            val restored =
                playback.state.first { state ->
                    val player = state.player
                    if (player is PlayerState.Loading) restoring = player.reason == LoadReason.RESTORE
                    (restoring && player is PlayerState.Paused) || player.isPlayedByHand()
                }
            if (restored.player !is PlayerState.Paused || !restoring) return@launch
            val autoResume =
                settings.state
                    .filterIsInstance<SettingsState.Ready>()
                    .first()
                    .values.playback.autoResume
            if (autoResume) playback.send(PlaybackCommand.Resume)
        }
    }

    /** Something other than the restore took the player first: a play the person asked for wins. */
    private fun PlayerState.isPlayedByHand(): Boolean =
        this is PlayerState.Playing || this is PlayerState.Buffering || (this is PlayerState.Loading && reason != LoadReason.RESTORE)
}
