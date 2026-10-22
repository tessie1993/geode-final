package dev.geode.feature.settings.core

import dev.geode.feature.settings.api.AnalysisCommand
import dev.geode.feature.settings.api.AnalysisSettings
import dev.geode.feature.settings.api.BehaviorCommand
import dev.geode.feature.settings.api.BehaviorSettings
import dev.geode.feature.settings.api.EqualizerCommand
import dev.geode.feature.settings.api.EqualizerSettings
import dev.geode.feature.settings.api.ExportCommand
import dev.geode.feature.settings.api.ExportSettings
import dev.geode.feature.settings.api.FolderCommand
import dev.geode.feature.settings.api.FolderSettings
import dev.geode.feature.settings.api.LookCommand
import dev.geode.feature.settings.api.LookSettings
import dev.geode.feature.settings.api.Millibels
import dev.geode.feature.settings.api.Minutes
import dev.geode.feature.settings.api.OnboardingCommand
import dev.geode.feature.settings.api.OnboardingSettings
import dev.geode.feature.settings.api.PlaybackCommand
import dev.geode.feature.settings.api.PlaybackSettings
import dev.geode.feature.settings.api.SettingsCommand
import dev.geode.feature.settings.api.SettingsValues

/**
 * What each command does to the settings, and the ranges every value is kept in. Pure: the reducer
 * uses it for every change, and the handlers for what they read back from storage.
 */
object SettingsRules {
    /** [values] with [command] applied, every value kept in its range. */
    fun apply(
        values: SettingsValues,
        command: SettingsCommand,
    ): SettingsValues = inRange(changed(values, command))

    /**
     * [values] with every value moved to the nearest one its range allows, the equalizer given
     * exactly its bands, and a preset's own levels when one is chosen.
     */
    fun inRange(values: SettingsValues): SettingsValues =
        values.copy(
            look = values.look.inRange(),
            playback = values.playback.inRange(),
            equalizer = values.equalizer.inRange(),
            analysis = values.analysis.inRange(),
            behavior = values.behavior.inRange(),
        )

    private fun changed(
        values: SettingsValues,
        command: SettingsCommand,
    ): SettingsValues =
        when (command) {
            is LookCommand -> values.copy(look = look(values.look, command))
            is PlaybackCommand -> values.copy(playback = playback(values.playback, command))
            is EqualizerCommand -> values.copy(equalizer = equalizer(values.equalizer, command))
            is AnalysisCommand -> values.copy(analysis = analysis(values.analysis, command))
            is BehaviorCommand -> values.copy(behavior = behavior(values.behavior, command))
            is ExportCommand -> values.copy(export = export(values.export, command))
            is FolderCommand -> values.copy(folders = folders(values.folders, command))
            is OnboardingCommand -> values.copy(onboarding = onboarding(values.onboarding, command))
        }

    private fun look(
        look: LookSettings,
        command: LookCommand,
    ): LookSettings =
        when (command) {
            is LookCommand.SetAccentIntensity -> look.copy(accentIntensity = command.intensity)
            is LookCommand.SetBackgroundDim -> look.copy(backgroundDim = command.dim)
            is LookCommand.SetFontColor -> look.copy(fontColor = command.color)
            is LookCommand.SetTextScale -> look.copy(textScale = command.scale)
            is LookCommand.SetBarOpacity -> look.copy(barOpacity = command.opacity)
            is LookCommand.SetPlayerPosition -> look.copy(playerPosition = command.position)
            is LookCommand.SetCornerStyle -> look.copy(cornerStyle = command.style)
            is LookCommand.SetCompactPlayer -> look.copy(compactPlayer = command.on)
            is LookCommand.SetClearVisualsMenu -> look.copy(clearVisualsMenu = command.on)
            is LookCommand.SetAmbientMode -> look.copy(ambientMode = command.mode)
        }

    private fun playback(
        playback: PlaybackSettings,
        command: PlaybackCommand,
    ): PlaybackSettings =
        when (command) {
            is PlaybackCommand.SetSpeed -> playback.copy(speed = command.speed)
            is PlaybackCommand.SetPitch -> playback.copy(pitch = command.pitch)
            is PlaybackCommand.SetSkipSilence -> playback.copy(skipSilence = command.on)
            is PlaybackCommand.SetPauseWhenUnplugged -> playback.copy(pauseWhenUnplugged = command.on)
            is PlaybackCommand.SetKeepScreenOn -> playback.copy(keepScreenOn = command.on)
            is PlaybackCommand.SetAutoResume -> playback.copy(autoResume = command.on)
            is PlaybackCommand.SetResumeLongTracks -> playback.copy(resumeLongTracks = command.on)
            is PlaybackCommand.SetSleepTimer -> playback.copy(sleepTimer = command.length)
            is PlaybackCommand.SetSleepFinishTrack -> playback.copy(sleepFinishTrack = command.on)
            is PlaybackCommand.SetFade -> playback.copy(fade = command.fade)
            is PlaybackCommand.SetReplayGain -> playback.copy(replayGain = command.mode)
            is PlaybackCommand.SetReplayGainPreamp -> playback.copy(replayGainPreamp = command.preamp)
            is PlaybackCommand.SetReplayGainClipGuard -> playback.copy(replayGainClipGuard = command.on)
            is PlaybackCommand.SetNativeEngine -> playback.copy(nativeEngine = command.on)
            is PlaybackCommand.SetGapless -> playback.copy(gapless = command.on)
            is PlaybackCommand.SetCrossfade -> playback.copy(crossfade = command.crossfade)
            is PlaybackCommand.SetCrossfadeCurve -> playback.copy(crossfadeCurve = command.curve)
        }

    private fun equalizer(
        equalizer: EqualizerSettings,
        command: EqualizerCommand,
    ): EqualizerSettings =
        when (command) {
            is EqualizerCommand.SetEnabled -> equalizer.copy(enabled = command.on)
            is EqualizerCommand.SetBand -> setBand(equalizer, command.band, command.level)
            is EqualizerCommand.UsePreset -> equalizer.copy(bands = command.preset.levels, preset = command.preset)
            is EqualizerCommand.SetBassBoost -> equalizer.copy(bassBoost = command.strength)
            is EqualizerCommand.SetLoudness -> equalizer.copy(loudness = command.gain)
        }

    private fun setBand(
        equalizer: EqualizerSettings,
        band: Int,
        level: Millibels,
    ): EqualizerSettings {
        if (band !in equalizer.bands.indices) return equalizer
        val bands = equalizer.bands.toMutableList().also { it[band] = level }
        return equalizer.copy(bands = bands, preset = null)
    }

    private fun analysis(
        analysis: AnalysisSettings,
        command: AnalysisCommand,
    ): AnalysisSettings =
        when (command) {
            is AnalysisCommand.SetBeatSensitivity -> analysis.copy(beatSensitivity = command.sensitivity)
            is AnalysisCommand.SetBeatMinInterval -> analysis.copy(beatMinIntervalMs = command.milliseconds)
            is AnalysisCommand.UseBeatTuning ->
                analysis.copy(beatSensitivity = command.tuning.sensitivity, beatMinIntervalMs = command.tuning.minIntervalMs)
            is AnalysisCommand.SetPresetMorph -> analysis.copy(presetMorph = command.length)
            is AnalysisCommand.SetReactToMicrophone -> analysis.copy(reactToMicrophone = command.on)
        }

    private fun behavior(
        behavior: BehaviorSettings,
        command: BehaviorCommand,
    ): BehaviorSettings =
        when (command) {
            is BehaviorCommand.SetTouchSmear -> behavior.copy(touchSmear = command.on)
            is BehaviorCommand.SetTouchSmearStrength -> behavior.copy(touchSmearStrength = command.strength)
            is BehaviorCommand.SetTouchTransform -> behavior.copy(touchTransform = command.on)
            is BehaviorCommand.SetReducedMotion -> behavior.copy(reducedMotion = command.on)
        }

    private fun export(
        export: ExportSettings,
        command: ExportCommand,
    ): ExportSettings =
        when (command) {
            is ExportCommand.SetQuality -> export.copy(quality = command.quality)
            is ExportCommand.SetFrameRate -> export.copy(frameRate = command.frameRate)
            is ExportCommand.SetRatio -> export.copy(ratio = command.ratio)
            is ExportCommand.SetLoopSafe -> export.copy(loopSafe = command.on)
            is ExportCommand.SetCodec -> export.copy(codec = command.codec)
            is ExportCommand.SetLoudness -> export.copy(loudness = command.loudness)
        }

    private fun folders(
        folders: FolderSettings,
        command: FolderCommand,
    ): FolderSettings =
        when (command) {
            is FolderCommand.SetPresetMirror -> folders.copy(presetMirror = command.folder)
            is FolderCommand.SetPresetFolder -> folders.copy(presetFolder = command.folder)
            is FolderCommand.AddMusicFolder -> folders.copy(musicFolders = folders.musicFolders + command.folder)
            is FolderCommand.RemoveMusicFolder -> folders.copy(musicFolders = folders.musicFolders - command.folder)
        }

    private fun onboarding(
        onboarding: OnboardingSettings,
        command: OnboardingCommand,
    ): OnboardingSettings =
        when (command) {
            OnboardingCommand.AcknowledgeSafety -> onboarding.copy(safetyAcknowledged = true)
            OnboardingCommand.FinishSetup -> onboarding.copy(setupDone = true)
            OnboardingCommand.FinishTutorial -> onboarding.copy(tutorialSeen = true)
            is OnboardingCommand.SetTutorialOnFirstRun -> onboarding.copy(tutorialOnFirstRun = command.on)
        }

    private fun LookSettings.inRange(): LookSettings =
        copy(
            accentIntensity = accentIntensity.coerceIn(LookSettings.ACCENT_INTENSITY),
            backgroundDim = backgroundDim.coerceIn(LookSettings.BACKGROUND_DIM),
            textScale = textScale.coerceIn(LookSettings.TEXT_SCALE),
            barOpacity = barOpacity.coerceIn(LookSettings.BAR_OPACITY),
        )

    private fun PlaybackSettings.inRange(): PlaybackSettings =
        copy(
            speed = speed.coerceIn(PlaybackSettings.SPEED),
            pitch = pitch.coerceIn(PlaybackSettings.PITCH),
            sleepTimer = sleepTimer.coerceAtLeast(NO_SLEEP_TIMER),
            fade = fade.coerceIn(PlaybackSettings.FADE),
            replayGainPreamp = replayGainPreamp.coerceIn(PlaybackSettings.REPLAY_GAIN_PREAMP),
            crossfade = crossfade.coerceIn(PlaybackSettings.CROSSFADE),
        )

    private fun EqualizerSettings.inRange(): EqualizerSettings {
        val levels = preset?.levels ?: bands
        return copy(
            bands = List(EqualizerSettings.BANDS) { (levels.getOrNull(it) ?: FLAT).coerceIn(EqualizerSettings.BAND_LEVEL) },
            bassBoost = bassBoost.coerceIn(EqualizerSettings.BASS_BOOST),
            loudness = loudness.coerceIn(EqualizerSettings.LOUDNESS),
        )
    }

    private fun AnalysisSettings.inRange(): AnalysisSettings =
        copy(
            beatSensitivity = beatSensitivity.coerceIn(AnalysisSettings.BEAT_SENSITIVITY),
            beatMinIntervalMs = beatMinIntervalMs.coerceIn(AnalysisSettings.BEAT_MIN_INTERVAL_MS),
            presetMorph = presetMorph.coerceIn(AnalysisSettings.PRESET_MORPH),
        )

    private fun BehaviorSettings.inRange(): BehaviorSettings =
        copy(touchSmearStrength = touchSmearStrength.coerceIn(BehaviorSettings.TOUCH_SMEAR_STRENGTH))

    private val NO_SLEEP_TIMER = Minutes(0)
    private val FLAT = Millibels(0)
}
