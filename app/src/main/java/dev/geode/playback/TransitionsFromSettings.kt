package dev.geode.playback

import dev.geode.engine.types.Millis
import dev.geode.feature.playback.api.ListeningOptions
import dev.geode.feature.playback.api.MAX_CROSSFADE
import dev.geode.feature.playback.api.Playback
import dev.geode.feature.playback.api.PlaybackCommand
import dev.geode.feature.settings.api.PlaybackSettings
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The crossfade and gapless choices belong to Settings (owner decision, #17, #28): Playback follows
 * them and keeps no copy of its own. Whenever what Playback joins tracks with differs from what
 * Settings hold (Settings changed, or Playback restored its listening options), Playback is told.
 * Resuming long tracks follows Settings the same way. The crossfade's curve goes from Settings to the
 * audio adapter directly.
 */
object TransitionsFromSettings {
    /** Follows Settings for as long as [scope] lives. */
    fun follow(
        settings: Settings,
        playback: Playback,
        scope: CoroutineScope,
    ) {
        scope.launch {
            combine(
                settings.state.filterIsInstance<SettingsState.Ready>(),
                playback.state
                    .map {
                        it.options
                    }.distinctUntilChanged(),
            ) { ready, options ->
                commandsFor(ready.values.playback, options)
            }.collect { commands -> commands.forEach(playback::send) }
        }
    }

    /** What Playback needs to be told so that it joins tracks as [settings] say; nothing when it already does. */
    fun commandsFor(
        settings: PlaybackSettings,
        options: ListeningOptions,
    ): List<PlaybackCommand> {
        // Playback holds the crossfade to its own range, so the comparison does too, or it would never agree.
        val crossfade = settings.crossfade.coerceIn(Millis.ZERO, MAX_CROSSFADE)
        return listOfNotNull(
            PlaybackCommand.SetCrossfade(crossfade).takeIf { crossfade != options.crossfade },
            PlaybackCommand.SetGapless(settings.gapless).takeIf { settings.gapless != options.gapless },
            PlaybackCommand
                .SetResumeLongTracks(settings.resumeLongTracks)
                .takeIf { settings.resumeLongTracks != options.resumeLongTracks },
        )
    }

    /** [options] as Playback's storage keeps them: without the crossfade and gapless, which Settings keep. */
    fun withoutTransitions(options: ListeningOptions): ListeningOptions =
        options.copy(
            crossfade = ListeningOptions().crossfade,
            gapless = ListeningOptions().gapless,
            resumeLongTracks = ListeningOptions().resumeLongTracks,
        )
}
