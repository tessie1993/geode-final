package dev.geode.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.geode.engine.types.Millis
import dev.geode.feature.settings.api.AmbientMode
import dev.geode.feature.settings.api.AnalysisCommand
import dev.geode.feature.settings.api.AnalysisSettings
import dev.geode.feature.settings.api.BackgroundCommand
import dev.geode.feature.settings.api.BeatTuning
import dev.geode.feature.settings.api.BehaviorCommand
import dev.geode.feature.settings.api.BehaviorSettings
import dev.geode.feature.settings.api.CornerStyle
import dev.geode.feature.settings.api.CrossfadeCurve
import dev.geode.feature.settings.api.Decibels
import dev.geode.feature.settings.api.EqualizerCommand
import dev.geode.feature.settings.api.EqualizerPreset
import dev.geode.feature.settings.api.EqualizerSettings
import dev.geode.feature.settings.api.ExportCodec
import dev.geode.feature.settings.api.ExportCommand
import dev.geode.feature.settings.api.ExportFrameRate
import dev.geode.feature.settings.api.ExportLoudness
import dev.geode.feature.settings.api.ExportQuality
import dev.geode.feature.settings.api.ExportRatio
import dev.geode.feature.settings.api.ExportSettings
import dev.geode.feature.settings.api.FolderCommand
import dev.geode.feature.settings.api.FolderSettings
import dev.geode.feature.settings.api.FolderUri
import dev.geode.feature.settings.api.LookCommand
import dev.geode.feature.settings.api.LookSettings
import dev.geode.feature.settings.api.Millibels
import dev.geode.feature.settings.api.Minutes
import dev.geode.feature.settings.api.OnboardingCommand
import dev.geode.feature.settings.api.OnboardingSettings
import dev.geode.feature.settings.api.PerformanceCommand
import dev.geode.feature.settings.api.PerformanceSettings
import dev.geode.feature.settings.api.PlaybackCommand
import dev.geode.feature.settings.api.PlaybackSettings
import dev.geode.feature.settings.api.PlayerPosition
import dev.geode.feature.settings.api.ReplayGainMode
import dev.geode.feature.settings.api.Seconds
import dev.geode.feature.settings.api.Semitones
import dev.geode.feature.settings.api.SettingsCommand
import dev.geode.feature.settings.api.SettingsValues
import dev.geode.feature.settings.api.TiltAmount
import dev.geode.feature.settings.api.VisualFrameRate
import dev.geode.feature.settings.api.VisualQuality
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val MILLIS_PER_SECOND = 1_000f
private const val MILLIBELS_PER_DECIBEL = 100f
private const val BASS_BOOST_FULL = 1_000f
private val NO_SLEEP_TIMER = Minutes(0)

/** Theme (was Look): the Element, accent, text and layout of the App UI. */
internal fun LazyListScope.themeSection(
    look: LookSettings,
    fontColors: List<FontColorChoice>,
    change: (SettingsCommand) -> Unit,
) {
    item {
        SettingsGroup(stringResource(R.string.look_group_element)) {
            ElementPicker(look.element) { change(LookCommand.SetElement(it)) }
        }
    }
    item {
        SettingsGroup(stringResource(R.string.look_group_colour)) {
            SliderRow(
                stringResource(R.string.look_accent_intensity, (look.accentIntensity * PERCENT).roundToInt()),
                look.accentIntensity,
                LookSettings.ACCENT_INTENSITY,
            ) { change(LookCommand.SetAccentIntensity(it)) }
            SliderRow(
                stringResource(R.string.look_background_dim, (look.backgroundDim * PERCENT).roundToInt()),
                look.backgroundDim,
                LookSettings.BACKGROUND_DIM,
            ) { change(LookCommand.SetBackgroundDim(it)) }
        }
    }
    item {
        SettingsGroup(stringResource(R.string.look_group_text)) {
            ChoiceRow(
                stringResource(R.string.look_font_color),
                fontColors,
                fontColors.firstOrNull { it.color == look.fontColor },
                { it?.name ?: stringResource(R.string.look_font_color_theme) },
            ) { change(LookCommand.SetFontColor(it?.color)) }
            SliderRow(
                stringResource(R.string.look_text_size, (look.textScale * PERCENT).roundToInt()),
                look.textScale,
                LookSettings.TEXT_SCALE,
            ) { change(LookCommand.SetTextScale(it)) }
        }
    }
    item {
        SettingsGroup(stringResource(R.string.look_group_layout)) {
            SliderRow(
                stringResource(R.string.look_bar_opacity, (look.barOpacity * PERCENT).roundToInt()),
                look.barOpacity,
                LookSettings.BAR_OPACITY,
            ) { change(LookCommand.SetBarOpacity(it)) }
            ChoiceRow(stringResource(R.string.look_player_position), PlayerPosition.entries, look.playerPosition, { positionName(it) }) {
                change(LookCommand.SetPlayerPosition(it))
            }
            ChoiceRow(stringResource(R.string.look_corner_style), CornerStyle.entries, look.cornerStyle, { cornerName(it) }) {
                change(LookCommand.SetCornerStyle(it))
            }
            SwitchRow(stringResource(R.string.look_compact_player), look.compactPlayer) { change(LookCommand.SetCompactPlayer(it)) }
            SwitchRow(stringResource(R.string.look_clear_visuals_menu), look.clearVisualsMenu) {
                change(LookCommand.SetClearVisualsMenu(it))
            }
        }
    }
    item { HomeGroup(look.ambientMode, change) }
}

/** Ambient: how much Home's Hero scene moves by itself. Its own group, so its screenshot shows it whole. */
@Composable
internal fun HomeGroup(
    ambientMode: AmbientMode,
    change: (SettingsCommand) -> Unit,
) {
    SettingsGroup(stringResource(R.string.look_group_home)) {
        ChoiceRow(stringResource(R.string.look_ambient_mode), AmbientMode.entries, ambientMode, { ambientName(it) }) {
            change(LookCommand.SetAmbientMode(it))
        }
    }
}

internal fun LazyListScope.audioSection(
    values: SettingsValues,
    change: (SettingsCommand) -> Unit,
) {
    item { SettingsGroup(stringResource(R.string.audio_group_playback)) { PlaybackGroup(values.playback, change) } }
    item { SettingsGroup(stringResource(R.string.audio_group_equalizer)) { EqualizerGroup(values.equalizer, change) } }
    item { SettingsGroup(stringResource(R.string.audio_group_analysis)) { AnalysisGroup(values.analysis, change) } }
}

@Composable
private fun PlaybackGroup(
    playback: PlaybackSettings,
    change: (SettingsCommand) -> Unit,
) {
    SliderRow(stringResource(R.string.playback_speed, playback.speed), playback.speed, PlaybackSettings.SPEED) {
        change(PlaybackCommand.SetSpeed(it))
    }
    SliderRow(
        stringResource(R.string.playback_pitch, playback.pitch.value),
        playback.pitch.value,
        PlaybackSettings.PITCH.start.value..PlaybackSettings.PITCH.endInclusive.value,
    ) { change(PlaybackCommand.SetPitch(Semitones(it))) }
    SliderRow(
        stringResource(R.string.playback_fade, playback.fade.value / MILLIS_PER_SECOND),
        playback.fade.value.toFloat(),
        PlaybackSettings.FADE.millisForSlider(),
    ) { change(PlaybackCommand.SetFade(Millis(it.roundToLong()))) }
    SwitchRow(stringResource(R.string.playback_skip_silence), playback.skipSilence) { change(PlaybackCommand.SetSkipSilence(it)) }
    SwitchRow(stringResource(R.string.playback_pause_unplugged), playback.pauseWhenUnplugged) {
        change(PlaybackCommand.SetPauseWhenUnplugged(it))
    }
    SwitchRow(stringResource(R.string.playback_auto_resume), playback.autoResume) { change(PlaybackCommand.SetAutoResume(it)) }
    SwitchRow(stringResource(R.string.playback_resume_long_tracks), playback.resumeLongTracks) {
        change(PlaybackCommand.SetResumeLongTracks(it))
    }
    ChoiceRow(
        stringResource(R.string.playback_sleep_timer),
        listOf(NO_SLEEP_TIMER) + PlaybackSettings.SLEEP_TIMER_CHOICES,
        playback.sleepTimer,
        { if (it == NO_SLEEP_TIMER) stringResource(R.string.settings_off) else stringResource(R.string.playback_sleep_minutes, it.value) },
    ) { change(PlaybackCommand.SetSleepTimer(it)) }
    SwitchRow(stringResource(R.string.playback_sleep_finish_track), playback.sleepFinishTrack) {
        change(PlaybackCommand.SetSleepFinishTrack(it))
    }
    ChoiceRow(stringResource(R.string.playback_replaygain), ReplayGainMode.entries, playback.replayGain, { replayGainName(it) }) {
        change(PlaybackCommand.SetReplayGain(it))
    }
    SliderRow(
        stringResource(R.string.playback_replaygain_preamp, playback.replayGainPreamp.value),
        playback.replayGainPreamp.value,
        PlaybackSettings.REPLAY_GAIN_PREAMP.start.value..PlaybackSettings.REPLAY_GAIN_PREAMP.endInclusive.value,
    ) { change(PlaybackCommand.SetReplayGainPreamp(Decibels(it))) }
    SwitchRow(stringResource(R.string.playback_replaygain_clip_guard), playback.replayGainClipGuard) {
        change(PlaybackCommand.SetReplayGainClipGuard(it))
    }
    SwitchRow(stringResource(R.string.playback_native_engine), playback.nativeEngine) { change(PlaybackCommand.SetNativeEngine(it)) }
    SwitchRow(stringResource(R.string.playback_gapless), playback.gapless) { change(PlaybackCommand.SetGapless(it)) }
    SliderRow(
        stringResource(R.string.playback_crossfade, playback.crossfade.value / MILLIS_PER_SECOND),
        playback.crossfade.value.toFloat(),
        PlaybackSettings.CROSSFADE.millisForSlider(),
    ) { change(PlaybackCommand.SetCrossfade(Millis(it.roundToLong()))) }
    ChoiceRow(stringResource(R.string.playback_crossfade_curve), CrossfadeCurve.entries, playback.crossfadeCurve, { curveName(it) }) {
        change(PlaybackCommand.SetCrossfadeCurve(it))
    }
}

@Composable
private fun EqualizerGroup(
    equalizer: EqualizerSettings,
    change: (SettingsCommand) -> Unit,
) {
    SwitchRow(stringResource(R.string.eq_enabled), equalizer.enabled) { change(EqualizerCommand.SetEnabled(it)) }
    ChoiceRow(stringResource(R.string.eq_preset), listOf(null) + EqualizerPreset.entries, equalizer.preset, { presetName(it) }) {
        it?.let { preset -> change(EqualizerCommand.UsePreset(preset)) }
    }
    val levels = EqualizerSettings.BAND_LEVEL.millibelsForSlider()
    equalizer.bands.forEachIndexed { band, level ->
        SliderRow(stringResource(R.string.eq_band, band + 1, level.value / MILLIBELS_PER_DECIBEL), level.value.toFloat(), levels) {
            change(EqualizerCommand.SetBand(band, Millibels(it.roundToInt())))
        }
    }
    SliderRow(
        stringResource(R.string.eq_bass_boost, (equalizer.bassBoost / BASS_BOOST_FULL * PERCENT).roundToInt()),
        equalizer.bassBoost.toFloat(),
        EqualizerSettings.BASS_BOOST.first.toFloat()..EqualizerSettings.BASS_BOOST.last.toFloat(),
    ) { change(EqualizerCommand.SetBassBoost(it.roundToInt())) }
    SliderRow(
        stringResource(R.string.eq_loudness, equalizer.loudness.value / MILLIBELS_PER_DECIBEL),
        equalizer.loudness.value.toFloat(),
        EqualizerSettings.LOUDNESS.millibelsForSlider(),
    ) { change(EqualizerCommand.SetLoudness(Millibels(it.roundToInt()))) }
}

@Composable
private fun AnalysisGroup(
    analysis: AnalysisSettings,
    change: (SettingsCommand) -> Unit,
) {
    SliderRow(
        stringResource(R.string.audio_beat_sensitivity, analysis.beatSensitivity),
        analysis.beatSensitivity,
        AnalysisSettings.BEAT_SENSITIVITY,
    ) { change(AnalysisCommand.SetBeatSensitivity(it)) }
    SliderRow(
        stringResource(R.string.audio_beat_min_gap, analysis.beatMinIntervalMs.roundToInt()),
        analysis.beatMinIntervalMs,
        AnalysisSettings.BEAT_MIN_INTERVAL_MS,
    ) { change(AnalysisCommand.SetBeatMinInterval(it)) }
    Row(horizontalArrangement = Arrangement.spacedBy(SettingsDimens.chipGap)) {
        OutlinedButton(onClick = { change(AnalysisCommand.UseBeatTuning(BeatTuning.SLOW_TRACK)) }) {
            Text(stringResource(R.string.audio_slow_track))
        }
        TextButton(onClick = { change(AnalysisCommand.UseBeatTuning(BeatTuning.DEFAULT)) }) { Text(stringResource(R.string.audio_default)) }
    }
    SliderRow(
        stringResource(R.string.audio_preset_morph, analysis.presetMorph.value),
        analysis.presetMorph.value,
        AnalysisSettings.PRESET_MORPH.start.value..AnalysisSettings.PRESET_MORPH.endInclusive.value,
    ) { change(AnalysisCommand.SetPresetMorph(Seconds(it))) }
    SwitchRow(stringResource(R.string.audio_react_mic), analysis.reactToMicrophone) { change(AnalysisCommand.SetReactToMicrophone(it)) }
}

/** Export preferences (was Export): what new renders start with. */
internal fun LazyListScope.exportSection(
    export: ExportSettings,
    change: (SettingsCommand) -> Unit,
) {
    item {
        SettingsGroup(stringResource(R.string.export_group_defaults)) {
            ChoiceRow(stringResource(R.string.export_quality), ExportQuality.entries, export.quality, { qualityName(it) }) {
                change(ExportCommand.SetQuality(it))
            }
            ChoiceRow(
                stringResource(R.string.export_frame_rate),
                ExportFrameRate.entries,
                export.frameRate,
                { stringResource(R.string.export_fps, it.fps) },
            ) { change(ExportCommand.SetFrameRate(it)) }
            ChoiceRow(stringResource(R.string.export_aspect_ratio), ExportRatio.entries, export.ratio, { ratioName(it) }) {
                change(ExportCommand.SetRatio(it))
            }
            SwitchRow(stringResource(R.string.export_loop_safe), export.loopSafe) { change(ExportCommand.SetLoopSafe(it)) }
            ChoiceRow(stringResource(R.string.export_codec), ExportCodec.entries, export.codec, { codecName(it) }) {
                change(ExportCommand.SetCodec(it))
            }
            ChoiceRow(stringResource(R.string.export_loudness), ExportLoudness.entries, export.loudness, { loudnessName(it) }) {
                change(ExportCommand.SetLoudness(it))
            }
        }
    }
    item {
        SettingsGroup(stringResource(R.string.export_group_alerts)) {
            SwitchRow(stringResource(R.string.export_finished_alerts), export.finishedAlerts) {
                change(ExportCommand.SetFinishedAlerts(it))
            }
        }
    }
}

/**
 * Battery (#28, owner 2026-10-23): the CPU/GPU save mode, Visuals off (#196), and how sharp and how
 * often the Visual is drawn on screen. Save mode overrides both while it is on; cooling down when hot is automatic.
 */
internal fun LazyListScope.batterySection(
    performance: PerformanceSettings,
    change: (SettingsCommand) -> Unit,
) {
    item {
        SettingsGroup(stringResource(R.string.battery_group_save_mode)) {
            SwitchRow(stringResource(R.string.save_mode), performance.saveMode) { change(PerformanceCommand.SetSaveMode(it)) }
            Note(stringResource(R.string.save_mode_note))
        }
    }
    item {
        SettingsGroup(stringResource(R.string.battery_group_visuals_off)) {
            SwitchRow(stringResource(R.string.visuals_off), performance.visualsOff) { change(PerformanceCommand.SetVisualsOff(it)) }
            Note(stringResource(R.string.visuals_off_note))
        }
    }
    item {
        SettingsGroup(stringResource(R.string.cpu_gpu_group_visual)) {
            if (performance.saveMode) Note(stringResource(R.string.cpu_gpu_save_mode_on))
            ChoiceRow(
                stringResource(R.string.cpu_gpu_quality),
                VisualQuality.entries,
                performance.visualQuality,
                { visualQualityName(it) },
            ) { change(PerformanceCommand.SetVisualQuality(it)) }
            ChoiceRow(
                stringResource(R.string.cpu_gpu_frame_rate),
                VisualFrameRate.entries,
                performance.visualFrameRate,
                { visualFrameRateName(it) },
            ) { change(PerformanceCommand.SetVisualFrameRate(it)) }
        }
    }
    item {
        SettingsGroup(stringResource(R.string.cpu_gpu_group_heat)) {
            Note(stringResource(R.string.cpu_gpu_heat_note))
        }
    }
}

/**
 * Default paths (was Folders, #101): where Studio saves its videos (#21), the music folders, where
 * presets live, and the folder they are mirrored to.
 */
internal fun LazyListScope.defaultPathsSection(
    folders: FolderSettings,
    onAction: (SettingsAction) -> Unit,
) {
    item {
        // One folder for every Project. Studio's first-run step picks it; here it can be changed, not cleared.
        FolderChoiceGroup(
            title = stringResource(R.string.folders_group_video_folder),
            folder = folders.videoFolder,
            none = stringResource(R.string.folders_video_folder_none),
            clear = null,
            onPick = { onAction(SettingsAction.PickVideoFolder) },
            onClear = {},
        )
    }
    item {
        SettingsGroup(stringResource(R.string.folders_group_music)) {
            if (folders.musicFolders.isEmpty()) Note(stringResource(R.string.folders_music_none))
            folders.musicFolders.sortedBy { it.value }.forEach { folder ->
                Row {
                    Note(folder.value)
                    TextButton(onClick = { onAction(SettingsAction.RemoveMusicFolder(folder)) }) {
                        Text(stringResource(R.string.folders_music_remove))
                    }
                }
            }
            OutlinedButton(onClick = { onAction(SettingsAction.PickMusicFolder) }) { Text(stringResource(R.string.folders_music_add)) }
        }
    }
    item {
        FolderChoiceGroup(
            title = stringResource(R.string.folders_group_preset_folder),
            folder = folders.presetFolder,
            none = stringResource(R.string.folders_preset_folder_app),
            clear = stringResource(R.string.folders_preset_folder_reset),
            onPick = { onAction(SettingsAction.PickPresetFolder) },
            onClear = { onAction(SettingsAction.Change(FolderCommand.SetPresetFolder(null))) },
        )
    }
    item {
        FolderChoiceGroup(
            title = stringResource(R.string.folders_group_presets),
            folder = folders.presetMirror,
            none = stringResource(R.string.folders_presets_none),
            clear = stringResource(R.string.folders_presets_stop),
            onPick = { onAction(SettingsAction.PickPresetMirror) },
            onClear = { onAction(SettingsAction.Change(FolderCommand.SetPresetMirror(null))) },
        )
    }
}

/**
 * One chosen folder: shows it, or [none] when there is none, with a button to pick and one to [clear]
 * (none when [clear] is null).
 */
@Composable
private fun FolderChoiceGroup(
    title: String,
    folder: FolderUri?,
    none: String,
    clear: String?,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    SettingsGroup(title) {
        Note(folder?.value ?: none)
        Row(horizontalArrangement = Arrangement.spacedBy(SettingsDimens.chipGap)) {
            OutlinedButton(onClick = onPick) { Text(stringResource(R.string.folders_presets_choose)) }
            if (folder != null && clear != null) TextButton(onClick = onClear) { Text(clear) }
        }
    }
}

/**
 * Application preferences (was Behavior): keeping the screen on, what runs in the background (#28),
 * touch, motion and the live wallpaper.
 */
internal fun LazyListScope.applicationSection(
    values: SettingsValues,
    onAction: (SettingsAction) -> Unit,
) {
    val behavior = values.behavior
    val change: (SettingsCommand) -> Unit = { onAction(SettingsAction.Change(it)) }
    item {
        SettingsGroup(stringResource(R.string.application_group_screen)) {
            SwitchRow(stringResource(R.string.playback_keep_screen_on), values.playback.keepScreenOn) {
                change(PlaybackCommand.SetKeepScreenOn(it))
            }
        }
    }
    item {
        SettingsGroup(stringResource(R.string.application_group_background)) {
            SwitchRow(stringResource(R.string.background_music), values.background.music) { change(BackgroundCommand.SetMusic(it)) }
            SwitchRow(stringResource(R.string.background_renders), values.background.renders) {
                change(BackgroundCommand.SetRenders(it))
            }
            SwitchRow(stringResource(R.string.background_recording), values.background.recording) {
                change(BackgroundCommand.SetRecording(it))
            }
        }
    }
    item {
        SettingsGroup(stringResource(R.string.behavior_group_touch)) {
            SwitchRow(stringResource(R.string.behavior_touch_smear), behavior.touchSmear) { change(BehaviorCommand.SetTouchSmear(it)) }
            if (behavior.touchSmear) {
                SliderRow(
                    stringResource(R.string.behavior_smear_strength, (behavior.touchSmearStrength * PERCENT).roundToInt()),
                    behavior.touchSmearStrength,
                    BehaviorSettings.TOUCH_SMEAR_STRENGTH,
                ) { change(BehaviorCommand.SetTouchSmearStrength(it)) }
            }
            SwitchRow(stringResource(R.string.behavior_touch_transform), behavior.touchTransform) {
                change(BehaviorCommand.SetTouchTransform(it))
            }
            ChoiceRow(stringResource(R.string.behavior_tilt), TiltAmount.entries, behavior.tilt, { tiltName(it) }) {
                change(BehaviorCommand.SetTilt(it))
            }
            if (behavior.reducedMotion && behavior.tilt != TiltAmount.OFF) Note(stringResource(R.string.behavior_tilt_reduced_motion))
        }
    }
    item {
        SettingsGroup(stringResource(R.string.behavior_group_safety)) {
            Note(stringResource(R.string.behavior_flash_guidance))
            SwitchRow(stringResource(R.string.behavior_slow_motion), behavior.reducedMotion) {
                change(BehaviorCommand.SetReducedMotion(it))
            }
        }
    }
    item {
        SettingsGroup(stringResource(R.string.behavior_group_wallpaper)) {
            OutlinedButton(onClick = { onAction(SettingsAction.SetLiveWallpaper) }) {
                Text(stringResource(R.string.behavior_wallpaper_button))
            }
        }
    }
}

internal fun LazyListScope.helpSection(
    onboarding: OnboardingSettings,
    onAction: (SettingsAction) -> Unit,
) {
    item {
        SettingsGroup(stringResource(R.string.help_tutorial_group)) {
            val start = if (onboarding.tutorialSeen) R.string.help_tutorial_replay else R.string.help_tutorial_start
            OutlinedButton(onClick = { onAction(SettingsAction.StartTutorial) }) { Text(stringResource(start)) }
            SwitchRow(stringResource(R.string.help_tutorial_on_first_run), onboarding.tutorialOnFirstRun) {
                onAction(SettingsAction.Change(OnboardingCommand.SetTutorialOnFirstRun(it)))
            }
        }
    }
}

internal fun LazyListScope.aboutSection(appVersion: String) {
    item {
        SettingsGroup(stringResource(R.string.about_group)) {
            Text(stringResource(R.string.about_version, appVersion))
            Note(stringResource(R.string.about_private))
        }
    }
}

/** A range of milliseconds as a slider's range. */
private fun ClosedRange<Millis>.millisForSlider(): ClosedFloatingPointRange<Float> = start.value.toFloat()..endInclusive.value.toFloat()

/** A range of millibels as a slider's range. */
private fun ClosedRange<Millibels>.millibelsForSlider(): ClosedFloatingPointRange<Float> =
    start.value.toFloat()..endInclusive.value.toFloat()
