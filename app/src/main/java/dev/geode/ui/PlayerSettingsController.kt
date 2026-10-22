package dev.geode.ui

import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.geode.analysis.AnalysisEngine
import dev.geode.analysis.PlaybackMath
import dev.geode.audio.AudioFxController
import dev.geode.audio.AudioFxState
import dev.geode.data.PlayerPrefs
import dev.geode.data.PlayerPrefsRepository
import dev.geode.playback.ReplayGain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
internal class PlayerSettingsController(
    private val userData: UserDataRepository,
    private val playerPrefsRepository: PlayerPrefsRepository,
    private val scope: CoroutineScope,
    private val player: Player,
    private val engine: AnalysisEngine,
    private val audioFx: AudioFxController,
    private val replayGain: ReplayGain,
    private val host: Host,
) {
    interface Host {
        fun redecideCachedBeats(prefs: GuiPrefs)

        /** The crossfade or gapless choice changed: Playback joins the tracks (#17). */
        fun listeningChanged(prefs: PlayerPrefs)
    }

    val guiPrefs: StateFlow<GuiPrefs> = userData.guiPrefs

    val playerPrefs: StateFlow<PlayerPrefs> = playerPrefsRepository.prefs

    suspend fun loadedPlayerPrefs(): PlayerPrefs = playerPrefsRepository.loaded()

    private val _audioFxState = MutableStateFlow(audioFx.snapshot())
    val audioFxState: StateFlow<AudioFxState> = _audioFxState

    fun setGuiPrefs(prefs: GuiPrefs) {
        val previous = guiPrefs.value
        scope.launch { userData.setGuiPrefs(prefs) }
        engine.beatSensitivity = prefs.beatSensitivity
        engine.beatMinIntervalMs = prefs.effectiveBeatMinIntervalMs
        val sensitivityChanged =
            previous.beatSensitivity != prefs.beatSensitivity ||
                previous.effectiveBeatMinIntervalMs != prefs.effectiveBeatMinIntervalMs
        if (sensitivityChanged) host.redecideCachedBeats(prefs)
    }

    fun setPlayerPrefs(prefs: PlayerPrefs) {
        val previous = playerPrefs.value
        val p = prefs.coerced()
        scope.launch { playerPrefsRepository.update { p } }
        applyPlaybackPrefs(p)
        if (p.crossfadeMs != previous.crossfadeMs || p.gapless != previous.gapless) host.listeningChanged(p)
    }

    fun applyPlaybackPrefs(p: PlayerPrefs) {
        player.playbackParameters = PlaybackParameters(p.speed, PlaybackMath.semitonesToRatio(p.pitchSemitones))
        (player as? ExoPlayer)?.let {
            it.skipSilenceEnabled = p.skipSilence
            it.setHandleAudioBecomingNoisy(p.pauseOnNoisy)
        }
        replayGain.configure(p.replayGainMode, p.replayGainPreampDb, p.replayGainClipGuard)
    }

    fun refreshAudioFx() {
        _audioFxState.value = audioFx.snapshot()
    }

    fun setAudioFxEnabled(enabled: Boolean) {
        audioFx.setEnabled(enabled)
        refreshAudioFx()
    }

    fun setAudioFxBand(
        band: Int,
        levelMb: Int,
    ) {
        audioFx.setBandLevel(band, levelMb)
        refreshAudioFx()
    }

    fun useAudioFxPreset(index: Int) {
        audioFx.usePreset(index)
        refreshAudioFx()
    }

    fun setAudioFxBassBoost(strength: Int) {
        audioFx.setBassBoost(strength)
        refreshAudioFx()
    }

    fun setAudioFxLoudness(gainMb: Int) {
        audioFx.setLoudness(gainMb)
        refreshAudioFx()
    }
}
