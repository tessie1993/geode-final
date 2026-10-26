package dev.geode.feature.settings.api

import dev.geode.engine.types.Millis

/**
 * A change to one setting. Settings keeps every value in its range (the ranges are on each section
 * of [SettingsValues]), so a command may carry any value and the stored one is the nearest allowed.
 * Grouped by section, as the tabs are.
 */
sealed interface SettingsCommand

sealed interface LookCommand : SettingsCommand {
    data class SetThemeMode(
        val mode: ThemeMode,
    ) : LookCommand

    data class SetReduceTransparency(
        val on: Boolean,
    ) : LookCommand

    data class SetAccentIntensity(
        val intensity: Float,
    ) : LookCommand

    data class SetBackgroundDim(
        val dim: Float,
    ) : LookCommand

    /** Null goes back to the theme's own text colour. */
    data class SetFontColor(
        val color: Argb?,
    ) : LookCommand

    data class SetTextScale(
        val scale: Float,
    ) : LookCommand

    data class SetBarOpacity(
        val opacity: Float,
    ) : LookCommand

    data class SetPlayerPosition(
        val position: PlayerPosition,
    ) : LookCommand

    data class SetCornerStyle(
        val style: CornerStyle,
    ) : LookCommand

    data class SetCompactPlayer(
        val on: Boolean,
    ) : LookCommand

    data class SetClearVisualsMenu(
        val on: Boolean,
    ) : LookCommand
}

sealed interface PlaybackCommand : SettingsCommand {
    data class SetSpeed(
        val speed: Float,
    ) : PlaybackCommand

    data class SetPitch(
        val pitch: Semitones,
    ) : PlaybackCommand

    data class SetSkipSilence(
        val on: Boolean,
    ) : PlaybackCommand

    data class SetPauseWhenUnplugged(
        val on: Boolean,
    ) : PlaybackCommand

    data class SetKeepScreenOn(
        val on: Boolean,
    ) : PlaybackCommand

    data class SetAutoResume(
        val on: Boolean,
    ) : PlaybackCommand

    data class SetResumeLongTracks(
        val on: Boolean,
    ) : PlaybackCommand

    data class SetSleepTimer(
        val length: Minutes,
    ) : PlaybackCommand

    data class SetSleepFinishTrack(
        val on: Boolean,
    ) : PlaybackCommand

    data class SetFade(
        val fade: Millis,
    ) : PlaybackCommand

    data class SetReplayGain(
        val mode: ReplayGainMode,
    ) : PlaybackCommand

    data class SetReplayGainPreamp(
        val preamp: Decibels,
    ) : PlaybackCommand

    data class SetReplayGainClipGuard(
        val on: Boolean,
    ) : PlaybackCommand

    data class SetNativeEngine(
        val on: Boolean,
    ) : PlaybackCommand

    data class SetGapless(
        val on: Boolean,
    ) : PlaybackCommand

    data class SetCrossfade(
        val crossfade: Millis,
    ) : PlaybackCommand

    data class SetCrossfadeCurve(
        val curve: CrossfadeCurve,
    ) : PlaybackCommand
}

sealed interface EqualizerCommand : SettingsCommand {
    data class SetEnabled(
        val on: Boolean,
    ) : EqualizerCommand

    /** Moves one band by hand, which leaves the preset. A band outside the equalizer is ignored. */
    data class SetBand(
        val band: Int,
        val level: Millibels,
    ) : EqualizerCommand

    data class UsePreset(
        val preset: EqualizerPreset,
    ) : EqualizerCommand

    data class SetBassBoost(
        val strength: Int,
    ) : EqualizerCommand

    data class SetLoudness(
        val gain: Millibels,
    ) : EqualizerCommand
}

sealed interface AnalysisCommand : SettingsCommand {
    data class SetBeatSensitivity(
        val sensitivity: Float,
    ) : AnalysisCommand

    data class SetBeatMinInterval(
        val milliseconds: Float,
    ) : AnalysisCommand

    /** Sets both beat values from one tuning, such as the one for slow tracks. */
    data class UseBeatTuning(
        val tuning: BeatTuning,
    ) : AnalysisCommand

    data class SetPresetMorph(
        val length: Seconds,
    ) : AnalysisCommand

    data class SetReactToMicrophone(
        val on: Boolean,
    ) : AnalysisCommand
}

sealed interface BehaviorCommand : SettingsCommand {
    data class SetTouchSmear(
        val on: Boolean,
    ) : BehaviorCommand

    data class SetTouchSmearStrength(
        val strength: Float,
    ) : BehaviorCommand

    data class SetTouchTransform(
        val on: Boolean,
    ) : BehaviorCommand

    data class SetReducedMotion(
        val on: Boolean,
    ) : BehaviorCommand
}

sealed interface ExportCommand : SettingsCommand {
    data class SetQuality(
        val quality: ExportQuality,
    ) : ExportCommand

    data class SetFrameRate(
        val frameRate: ExportFrameRate,
    ) : ExportCommand

    data class SetRatio(
        val ratio: ExportRatio,
    ) : ExportCommand

    data class SetLoopSafe(
        val on: Boolean,
    ) : ExportCommand

    data class SetCodec(
        val codec: ExportCodec,
    ) : ExportCommand

    data class SetLoudness(
        val loudness: ExportLoudness,
    ) : ExportCommand

    data class SetFinishedAlerts(
        val on: Boolean,
    ) : ExportCommand
}

sealed interface FolderCommand : SettingsCommand {
    /** The folder Studio saves its videos to (#21). It can be changed but not cleared. */
    data class SetVideoFolder(
        val folder: FolderUri,
    ) : FolderCommand

    /** Null stops mirroring presets to a folder. */
    data class SetPresetMirror(
        val folder: FolderUri?,
    ) : FolderCommand

    /** Null keeps presets in the app's own storage. */
    data class SetPresetFolder(
        val folder: FolderUri?,
    ) : FolderCommand

    data class AddMusicFolder(
        val folder: FolderUri,
    ) : FolderCommand

    data class RemoveMusicFolder(
        val folder: FolderUri,
    ) : FolderCommand
}

/** Run in background (#28), in Application preferences. */
sealed interface BackgroundCommand : SettingsCommand {
    data class SetMusic(
        val on: Boolean,
    ) : BackgroundCommand

    data class SetRenders(
        val on: Boolean,
    ) : BackgroundCommand

    data class SetRecording(
        val on: Boolean,
    ) : BackgroundCommand
}

/** The CPU/GPU section, and the save mode switch in Application preferences (#28). */
sealed interface PerformanceCommand : SettingsCommand {
    data class SetVisualQuality(
        val quality: VisualQuality,
    ) : PerformanceCommand

    data class SetVisualFrameRate(
        val frameRate: VisualFrameRate,
    ) : PerformanceCommand

    data class SetSaveMode(
        val on: Boolean,
    ) : PerformanceCommand

    /** Visuals off, in Battery (#196). */
    data class SetVisualsOff(
        val on: Boolean,
    ) : PerformanceCommand
}

sealed interface OnboardingCommand : SettingsCommand {
    data object AcknowledgeSafety : OnboardingCommand

    data object FinishSetup : OnboardingCommand

    data object FinishTutorial : OnboardingCommand

    data class SetTutorialOnFirstRun(
        val on: Boolean,
    ) : OnboardingCommand
}
