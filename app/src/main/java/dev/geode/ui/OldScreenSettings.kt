package dev.geode.ui

import dev.geode.audio.AudioFxBand
import dev.geode.audio.AudioFxFormat
import dev.geode.audio.AudioFxState
import dev.geode.audio.dsp.DspSettings
import dev.geode.audio.dsp.NativeDspProcessor
import dev.geode.data.ExportDefaults
import dev.geode.data.PlayerPrefs
import dev.geode.engine.types.Millis
import dev.geode.export.LoudnessTarget
import dev.geode.feature.playback.api.ListeningOptions
import dev.geode.feature.settings.api.AnalysisCommand
import dev.geode.feature.settings.api.Argb
import dev.geode.feature.settings.api.BehaviorCommand
import dev.geode.feature.settings.api.CrossfadeCurve
import dev.geode.feature.settings.api.Decibels
import dev.geode.feature.settings.api.EqualizerCommand
import dev.geode.feature.settings.api.EqualizerPreset
import dev.geode.feature.settings.api.EqualizerSettings
import dev.geode.feature.settings.api.ExportCommand
import dev.geode.feature.settings.api.ExportFrameRate
import dev.geode.feature.settings.api.ExportLoudness
import dev.geode.feature.settings.api.ExportSettings
import dev.geode.feature.settings.api.FolderCommand
import dev.geode.feature.settings.api.FolderUri
import dev.geode.feature.settings.api.LookCommand
import dev.geode.feature.settings.api.Millibels
import dev.geode.feature.settings.api.Minutes
import dev.geode.feature.settings.api.OnboardingCommand
import dev.geode.feature.settings.api.PlaybackSettings
import dev.geode.feature.settings.api.ReplayGainMode
import dev.geode.feature.settings.api.Seconds
import dev.geode.feature.settings.api.Semitones
import dev.geode.feature.settings.api.SettingsCommand
import dev.geode.feature.settings.api.SettingsValues
import dev.geode.export.ExportCodec as OldCodec
import dev.geode.export.ExportQuality as OldQuality
import dev.geode.export.ExportRatio as OldRatio
import dev.geode.feature.settings.api.CornerStyle as SettingsCorner
import dev.geode.feature.settings.api.ExportCodec as SettingsCodec
import dev.geode.feature.settings.api.ExportQuality as SettingsQuality
import dev.geode.feature.settings.api.ExportRatio as SettingsRatio
import dev.geode.feature.settings.api.PlaybackCommand as SettingsPlayback
import dev.geode.feature.settings.api.PlayerPosition as SettingsPosition

/**
 * Settings' values as the old screens and controllers read them, and their old writes as Settings
 * Commands (#17, Settings slice).
 *
 * Settings is the one source of truth: an old screen still edits a whole [GuiPrefs] or [PlayerPrefs]
 * and saves it, and the difference from what Settings holds becomes one Command per changed value.
 * Each part goes when the last old screen using it is replaced by Settings' own screens.
 */
internal object OldScreenSettings {
    // The App UI's own settings.

    fun guiPrefsOf(values: SettingsValues): GuiPrefs =
        GuiPrefs(
            playerPosition = byName<PlayerPosition>(values.look.playerPosition) ?: GuiPrefs().playerPosition,
            cornerStyle = byName<CornerStyle>(values.look.cornerStyle) ?: GuiPrefs().cornerStyle,
            barOpacity = values.look.barOpacity,
            beatSensitivity = values.analysis.beatSensitivity,
            beatMinIntervalMs = values.analysis.beatMinIntervalMs,
            presetMirrorUri = values.folders.presetMirror?.value,
            presetMorphSeconds = values.analysis.presetMorph.value,
            accentIntensity = values.look.accentIntensity,
            backgroundDim = values.look.backgroundDim,
            compactPlayer = values.look.compactPlayer,
            clearVisualsMenu = values.look.clearVisualsMenu,
            fontColorArgb = values.look.fontColor?.value,
            textScale = values.look.textScale,
            safetyAcknowledged = values.onboarding.safetyAcknowledged,
            setupDone = values.onboarding.setupDone,
            tutorialSeen = values.onboarding.tutorialSeen,
            tutorialOnFirstRun = values.onboarding.tutorialOnFirstRun,
            reducedMotion = values.behavior.reducedMotion,
            micReactive = values.analysis.reactToMicrophone,
            touchSmear = values.behavior.touchSmear,
            touchSmearStrength = values.behavior.touchSmearStrength,
            touchTransform = values.behavior.touchTransform,
        )

    /** One Command for each value [after] changes from [before]. What Settings does not keep is not sent. */
    fun commandsFor(
        before: GuiPrefs,
        after: GuiPrefs,
    ): List<SettingsCommand> =
        buildList {
            fun <T> changed(
                read: (GuiPrefs) -> T,
                command: (T) -> SettingsCommand,
            ) {
                val value = read(after)
                if (read(before) != value) add(command(value))
            }
            // A value Settings does not know is not sent, so what it keeps is not overwritten.
            if (before.playerPosition != after.playerPosition) {
                byName<SettingsPosition>(after.playerPosition)?.let { add(LookCommand.SetPlayerPosition(it)) }
            }
            if (before.cornerStyle != after.cornerStyle) {
                byName<SettingsCorner>(after.cornerStyle)?.let { add(LookCommand.SetCornerStyle(it)) }
            }
            changed({ it.barOpacity }) { LookCommand.SetBarOpacity(it) }
            changed({ it.accentIntensity }) { LookCommand.SetAccentIntensity(it) }
            changed({ it.backgroundDim }) { LookCommand.SetBackgroundDim(it) }
            changed({ it.compactPlayer }) { LookCommand.SetCompactPlayer(it) }
            changed({ it.clearVisualsMenu }) { LookCommand.SetClearVisualsMenu(it) }
            changed({ it.fontColorOverride }) { LookCommand.SetFontColor(it?.let(::Argb)) }
            changed({ it.textScale }) { LookCommand.SetTextScale(it) }
            changed({ it.beatSensitivity }) { AnalysisCommand.SetBeatSensitivity(it) }
            changed({ it.beatMinIntervalMs }) { AnalysisCommand.SetBeatMinInterval(it) }
            changed({ it.presetMorphSeconds }) { AnalysisCommand.SetPresetMorph(Seconds(it)) }
            changed({ it.micReactive }) { AnalysisCommand.SetReactToMicrophone(it) }
            changed({ it.presetMirrorUri }) { FolderCommand.SetPresetMirror(it?.let(::FolderUri)) }
            changed({ it.reducedMotion }) { BehaviorCommand.SetReducedMotion(it) }
            changed({ it.touchSmear }) { BehaviorCommand.SetTouchSmear(it) }
            changed({ it.touchSmearStrength }) { BehaviorCommand.SetTouchSmearStrength(it) }
            changed({ it.touchTransform }) { BehaviorCommand.SetTouchTransform(it) }
            changed({ it.tutorialOnFirstRun }) { OnboardingCommand.SetTutorialOnFirstRun(it) }
            // What the person has been through is never undone.
            if (after.safetyAcknowledged && !before.safetyAcknowledged) add(OnboardingCommand.AcknowledgeSafety)
            if (after.setupDone && !before.setupDone) add(OnboardingCommand.FinishSetup)
            if (after.tutorialSeen && !before.tutorialSeen) add(OnboardingCommand.FinishTutorial)
        }

    // The player's settings. Shuffle and repeat belong to Playback (#35), which [listening] reads.

    fun playerPrefsOf(
        playback: PlaybackSettings,
        listening: ListeningOptions,
    ): PlayerPrefs =
        PlayerPrefs(
            shuffle = listening.shuffle,
            repeatMode = OldScreenModels.repeatModeOf(listening.repeat),
            speed = playback.speed,
            pitchSemitones = playback.pitch.value,
            skipSilence = playback.skipSilence,
            pauseOnNoisy = playback.pauseWhenUnplugged,
            keepScreenOn = playback.keepScreenOn,
            autoResume = playback.autoResume,
            sleepTimerMinutes = playback.sleepTimer.value,
            sleepFinishTrack = playback.sleepFinishTrack,
            fadeMs = playback.fade.value.toInt(),
            replayGainMode = playback.replayGain.ordinal,
            replayGainPreampDb = playback.replayGainPreamp.value,
            replayGainClipGuard = playback.replayGainClipGuard,
            nativeEngine = playback.nativeEngine,
            crossfadeMs = playback.crossfade.value.toInt(),
            crossfadeCurve = playback.crossfadeCurve.ordinal,
            gapless = playback.gapless,
            resumeLongTracks = playback.resumeLongTracks,
        )

    fun commandsFor(
        before: PlayerPrefs,
        after: PlayerPrefs,
    ): List<SettingsCommand> =
        buildList {
            fun <T> changed(
                read: (PlayerPrefs) -> T,
                command: (T) -> SettingsCommand,
            ) {
                val value = read(after)
                if (read(before) != value) add(command(value))
            }
            changed({ it.speed }) { SettingsPlayback.SetSpeed(it) }
            changed({ it.pitchSemitones }) { SettingsPlayback.SetPitch(Semitones(it)) }
            changed({ it.skipSilence }) { SettingsPlayback.SetSkipSilence(it) }
            changed({ it.pauseOnNoisy }) { SettingsPlayback.SetPauseWhenUnplugged(it) }
            changed({ it.keepScreenOn }) { SettingsPlayback.SetKeepScreenOn(it) }
            changed({ it.autoResume }) { SettingsPlayback.SetAutoResume(it) }
            changed({ it.sleepTimerMinutes }) { SettingsPlayback.SetSleepTimer(Minutes(it)) }
            changed({ it.sleepFinishTrack }) { SettingsPlayback.SetSleepFinishTrack(it) }
            changed({ it.fadeMs }) { SettingsPlayback.SetFade(Millis(it.toLong())) }
            changed({ it.replayGainMode }) { SettingsPlayback.SetReplayGain(ReplayGainMode.entries.getOrElse(it) { ReplayGainMode.OFF }) }
            changed({ it.replayGainPreampDb }) { SettingsPlayback.SetReplayGainPreamp(Decibels(it)) }
            changed({ it.replayGainClipGuard }) { SettingsPlayback.SetReplayGainClipGuard(it) }
            changed({ it.nativeEngine }) { SettingsPlayback.SetNativeEngine(it) }
            changed({ it.crossfadeMs }) { SettingsPlayback.SetCrossfade(Millis(it.toLong())) }
            changed({ it.crossfadeCurve }) {
                SettingsPlayback.SetCrossfadeCurve(CrossfadeCurve.entries.getOrElse(it) { CrossfadeCurve.EQUAL_POWER })
            }
            changed({ it.gapless }) { SettingsPlayback.SetGapless(it) }
            changed({ it.resumeLongTracks }) { SettingsPlayback.SetResumeLongTracks(it) }
        }

    // The equalizer.

    /** What the equalizer runs: Settings' bands, boost and loudness over the processor's other stages. */
    fun dspOf(
        equalizer: EqualizerSettings,
        current: DspSettings,
    ): DspSettings =
        current.copy(
            enabled = equalizer.enabled,
            bandsMb = List(NativeDspProcessor.BANDS) { band -> equalizer.bands.getOrNull(band)?.value ?: 0 },
            bassBoost = equalizer.bassBoost,
            loudnessMb = equalizer.loudness.value,
        )

    /** A band's name on the old equalizer panel: its centre, which only the native chain knows. */
    fun nativeBandLabel(band: Int): String = AudioFxFormat.freqLabel(NativeDspProcessor.bandCenterMilliHz(band))

    /**
     * The old equalizer panel's state. [presetNames] are the presets' names, in [EqualizerPreset] order;
     * [bandLabel] names each band ([nativeBandLabel] in the app).
     */
    fun audioFxStateOf(
        equalizer: EqualizerSettings,
        presetNames: List<String>,
        bandLabel: (Int) -> String,
    ): AudioFxState =
        AudioFxState(
            available = true,
            attached = true,
            bassAvailable = true,
            loudnessAvailable = true,
            enabled = equalizer.enabled,
            bands =
                equalizer.bands.mapIndexed { band, level ->
                    AudioFxBand(
                        bandLabel(band),
                        level.value,
                        EqualizerSettings.BAND_LEVEL.start.value,
                        EqualizerSettings.BAND_LEVEL.endInclusive.value,
                    )
                },
            presets = presetNames,
            presetIndex = equalizer.preset?.ordinal ?: -1,
            bassBoost = equalizer.bassBoost,
            loudness = equalizer.loudness.value,
        )

    fun bandCommand(
        band: Int,
        levelMb: Int,
    ): SettingsCommand = EqualizerCommand.SetBand(band, Millibels(levelMb))

    fun presetCommand(index: Int): SettingsCommand? = EqualizerPreset.entries.getOrNull(index)?.let(EqualizerCommand::UsePreset)

    // Export defaults.

    fun exportDefaultsOf(export: ExportSettings): ExportDefaults =
        ExportDefaults(
            quality = byName<OldQuality>(export.quality) ?: ExportDefaults().quality,
            fps = export.frameRate.fps,
            ratio = byName<OldRatio>(export.ratio) ?: ExportDefaults().ratio,
            loopSafe = export.loopSafe,
            codec = byName<OldCodec>(export.codec) ?: ExportDefaults().codec,
            loudnessTargetId = LOUDNESS_IDS[export.loudness] ?: ExportDefaults().loudnessTargetId,
        )

    fun commandsFor(
        before: ExportDefaults,
        after: ExportDefaults,
    ): List<SettingsCommand> =
        buildList {
            if (before.quality != after.quality) byName<SettingsQuality>(after.quality)?.let { add(ExportCommand.SetQuality(it)) }
            if (before.fps !=
                after.fps
            ) {
                add(
                    ExportCommand.SetFrameRate(ExportFrameRate.entries.firstOrNull { it.fps == after.fps } ?: ExportFrameRate.FPS60),
                )
            }
            if (before.ratio != after.ratio) byName<SettingsRatio>(after.ratio)?.let { add(ExportCommand.SetRatio(it)) }
            if (before.loopSafe != after.loopSafe) add(ExportCommand.SetLoopSafe(after.loopSafe))
            if (before.codec != after.codec) byName<SettingsCodec>(after.codec)?.let { add(ExportCommand.SetCodec(it)) }
            if (before.loudnessTargetId != after.loudnessTargetId) {
                val loudness = LOUDNESS_IDS.entries.firstOrNull { it.value == after.loudnessTargetId }?.key ?: ExportLoudness.LEAVE_AS_IS
                add(ExportCommand.SetLoudness(loudness))
            }
        }

    /**
     * [value]'s namesake in [E], or null when [E] has none. The old screens' enums and Settings' grow
     * apart (a quality one side adds before the other), so no mapping by name may throw.
     */
    inline fun <reified E : Enum<E>> byName(value: Enum<*>): E? = enumValues<E>().firstOrNull { it.name == value.name }

    private val LOUDNESS_IDS: Map<ExportLoudness, String> =
        mapOf(
            ExportLoudness.YOUTUBE to LoudnessTarget.YouTube.id,
            ExportLoudness.SHORTS_AND_TIKTOK to LoudnessTarget.ShortsAndTikTok.id,
            ExportLoudness.LEAVE_AS_IS to LoudnessTarget.LeaveAsIs.id,
        )
}
