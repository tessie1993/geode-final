package dev.geode.audio

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.geode.analysis.PlaybackMath
import dev.geode.feature.settings.api.AnalysisSettings
import dev.geode.feature.settings.api.PlaybackSettings
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.playback.PlaybackSession
import dev.geode.render.VisualSafety
import dev.geode.ui.OldScreenSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Applies Settings to the audio engine as they change (#17, Settings slice): the player's speed,
 * pitch, silence skipping and headphone handling, ReplayGain, the equalizer, and the beat detection
 * the Visual reacts to. Settings is the only source; nothing here stores anything.
 *
 * Started once the engine's session opens, and runs for the life of the process, as the engine does.
 */
@OptIn(UnstableApi::class)
class EngineTuning(
    private val settings: Settings,
    private val session: PlaybackSession,
    private val scope: CoroutineScope,
) {
    fun start() {
        val ready = settings.state.filterIsInstance<SettingsState.Ready>().map { it.values }
        scope.launch { ready.map { it.playback }.distinctUntilChanged().collect(::applyPlayback) }
        scope.launch {
            ready.map { it.equalizer }.distinctUntilChanged().collect { equalizer ->
                session.audioFx.apply(OldScreenSettings.dspOf(equalizer, session.audioFx.current()))
            }
        }
        scope.launch { ready.map { it.analysis }.distinctUntilChanged().collect(::applyAnalysis) }
    }

    private fun applyPlayback(playback: PlaybackSettings) {
        val player = session.player
        player.playbackParameters = PlaybackParameters(playback.speed, PlaybackMath.semitonesToRatio(playback.pitch.value))
        (player as? ExoPlayer)?.let {
            it.skipSilenceEnabled = playback.skipSilence
            it.setHandleAudioBecomingNoisy(playback.pauseWhenUnplugged)
        }
        session.replayGain.configure(playback.replayGain.ordinal, playback.replayGainPreamp.value, playback.replayGainClipGuard)
    }

    private fun applyAnalysis(analysis: AnalysisSettings) {
        session.analysis.beatSensitivity = analysis.beatSensitivity
        session.analysis.beatMinIntervalMs = VisualSafety.beatMinIntervalMs(analysis.beatMinIntervalMs)
    }
}
