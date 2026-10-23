package dev.geode.feature.settings.core

import dev.geode.engine.types.Millis
import dev.geode.feature.settings.api.AnalysisCommand
import dev.geode.feature.settings.api.AnalysisSettings
import dev.geode.feature.settings.api.Argb
import dev.geode.feature.settings.api.BackgroundCommand
import dev.geode.feature.settings.api.BackgroundSettings
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
import dev.geode.feature.settings.api.VisualFrameRate
import dev.geode.feature.settings.api.VisualQuality
import org.junit.Assert.assertEquals
import org.junit.Test

/** Every command against the defaults, as a table: what it changes, and the range it is kept in. */
class SettingsRulesTest {
    private val defaults = SettingsValues()
    private val look = LookSettings()
    private val playback = PlaybackSettings()
    private val equalizer = EqualizerSettings()
    private val analysis = AnalysisSettings()
    private val behavior = BehaviorSettings()
    private val folders = FolderSettings()
    private val onboarding = OnboardingSettings()
    private val background = BackgroundSettings()
    private val performance = PerformanceSettings()
    private val night = Argb(0xFFBFE2F2.toInt())
    private val music = FolderUri("content://tree/music")
    private val more = FolderUri("content://tree/more")
    private val presets = FolderUri("content://tree/presets")
    private val videos = FolderUri("content://tree/videos")

    private val lookRows: List<Row> =
        listOf(
            Row(LookCommand.SetAccentIntensity(1.2f), defaults.copy(look = look.copy(accentIntensity = 1.2f))),
            Row(LookCommand.SetAccentIntensity(0.1f), defaults.copy(look = look.copy(accentIntensity = 0.5f))),
            Row(LookCommand.SetAccentIntensity(9f), defaults.copy(look = look.copy(accentIntensity = 1.5f))),
            Row(LookCommand.SetBackgroundDim(0.3f), defaults.copy(look = look.copy(backgroundDim = 0.3f))),
            Row(LookCommand.SetBackgroundDim(-1f), defaults),
            Row(LookCommand.SetBackgroundDim(0.9f), defaults.copy(look = look.copy(backgroundDim = 0.6f))),
            Row(LookCommand.SetFontColor(night), defaults.copy(look = look.copy(fontColor = night))),
            Row(LookCommand.SetFontColor(null), defaults),
            Row(LookCommand.SetTextScale(1.1f), defaults.copy(look = look.copy(textScale = 1.1f))),
            Row(LookCommand.SetTextScale(0.5f), defaults.copy(look = look.copy(textScale = 0.85f))),
            Row(LookCommand.SetTextScale(2f), defaults.copy(look = look.copy(textScale = 1.3f))),
            Row(LookCommand.SetBarOpacity(0.4f), defaults.copy(look = look.copy(barOpacity = 0.4f))),
            Row(LookCommand.SetBarOpacity(0f), defaults.copy(look = look.copy(barOpacity = 0.2f))),
            Row(LookCommand.SetBarOpacity(3f), defaults.copy(look = look.copy(barOpacity = 1f))),
            Row(LookCommand.SetPlayerPosition(PlayerPosition.TOP), defaults.copy(look = look.copy(playerPosition = PlayerPosition.TOP))),
            Row(LookCommand.SetCornerStyle(CornerStyle.PILL), defaults.copy(look = look.copy(cornerStyle = CornerStyle.PILL))),
            Row(LookCommand.SetCompactPlayer(true), defaults.copy(look = look.copy(compactPlayer = true))),
            Row(LookCommand.SetClearVisualsMenu(true), defaults.copy(look = look.copy(clearVisualsMenu = true))),
        )

    private val playbackRows: List<Row> =
        listOf(
            Row(PlaybackCommand.SetSpeed(1.5f), defaults.copy(playback = playback.copy(speed = 1.5f))),
            Row(PlaybackCommand.SetSpeed(0.1f), defaults.copy(playback = playback.copy(speed = 0.5f))),
            Row(PlaybackCommand.SetSpeed(4f), defaults.copy(playback = playback.copy(speed = 2f))),
            Row(PlaybackCommand.SetPitch(Semitones(2.5f)), defaults.copy(playback = playback.copy(pitch = Semitones(2.5f)))),
            Row(PlaybackCommand.SetPitch(Semitones(-12f)), defaults.copy(playback = playback.copy(pitch = Semitones(-6f)))),
            Row(PlaybackCommand.SetPitch(Semitones(12f)), defaults.copy(playback = playback.copy(pitch = Semitones(6f)))),
            Row(PlaybackCommand.SetSkipSilence(true), defaults.copy(playback = playback.copy(skipSilence = true))),
            Row(PlaybackCommand.SetPauseWhenUnplugged(false), defaults.copy(playback = playback.copy(pauseWhenUnplugged = false))),
            Row(PlaybackCommand.SetKeepScreenOn(true), defaults.copy(playback = playback.copy(keepScreenOn = true))),
            Row(PlaybackCommand.SetAutoResume(true), defaults.copy(playback = playback.copy(autoResume = true))),
            Row(PlaybackCommand.SetResumeLongTracks(false), defaults.copy(playback = playback.copy(resumeLongTracks = false))),
            Row(PlaybackCommand.SetSleepTimer(Minutes(30)), defaults.copy(playback = playback.copy(sleepTimer = Minutes(30)))),
            Row(PlaybackCommand.SetSleepTimer(Minutes(-5)), defaults),
            Row(PlaybackCommand.SetSleepFinishTrack(true), defaults.copy(playback = playback.copy(sleepFinishTrack = true))),
            Row(PlaybackCommand.SetFade(Millis(1_500)), defaults.copy(playback = playback.copy(fade = Millis(1_500)))),
            Row(PlaybackCommand.SetFade(Millis(-1)), defaults),
            Row(PlaybackCommand.SetFade(Millis(60_000)), defaults.copy(playback = playback.copy(fade = Millis(6_000)))),
            Row(
                PlaybackCommand.SetReplayGain(ReplayGainMode.ALBUM),
                defaults.copy(playback = playback.copy(replayGain = ReplayGainMode.ALBUM)),
            ),
            Row(
                PlaybackCommand.SetReplayGainPreamp(Decibels(-3.5f)),
                defaults.copy(playback = playback.copy(replayGainPreamp = Decibels(-3.5f))),
            ),
            Row(
                PlaybackCommand.SetReplayGainPreamp(Decibels(-40f)),
                defaults.copy(playback = playback.copy(replayGainPreamp = Decibels(-15f))),
            ),
            Row(
                PlaybackCommand.SetReplayGainPreamp(Decibels(40f)),
                defaults.copy(playback = playback.copy(replayGainPreamp = Decibels(15f))),
            ),
            Row(PlaybackCommand.SetReplayGainClipGuard(false), defaults.copy(playback = playback.copy(replayGainClipGuard = false))),
            Row(PlaybackCommand.SetNativeEngine(true), defaults.copy(playback = playback.copy(nativeEngine = true))),
            Row(PlaybackCommand.SetGapless(false), defaults.copy(playback = playback.copy(gapless = false))),
            Row(PlaybackCommand.SetCrossfade(Millis(4_000)), defaults.copy(playback = playback.copy(crossfade = Millis(4_000)))),
            Row(PlaybackCommand.SetCrossfade(Millis(-10)), defaults),
            Row(PlaybackCommand.SetCrossfade(Millis(99_000)), defaults.copy(playback = playback.copy(crossfade = Millis(12_000)))),
            Row(
                PlaybackCommand.SetCrossfadeCurve(CrossfadeCurve.SMOOTH),
                defaults.copy(playback = playback.copy(crossfadeCurve = CrossfadeCurve.SMOOTH)),
            ),
        )

    private val equalizerRows: List<Row> =
        listOf(
            Row(EqualizerCommand.SetEnabled(true), defaults.copy(equalizer = equalizer.copy(enabled = true))),
            Row(EqualizerCommand.SetBand(2, Millibels(300)), defaults.copy(equalizer = equalizer.copy(bands = bands(2 to 300)))),
            Row(EqualizerCommand.SetBand(0, Millibels(-9_000)), defaults.copy(equalizer = equalizer.copy(bands = bands(0 to -1_500)))),
            Row(EqualizerCommand.SetBand(9, Millibels(9_000)), defaults.copy(equalizer = equalizer.copy(bands = bands(9 to 1_500)))),
            Row(EqualizerCommand.SetBand(-1, Millibels(300)), defaults),
            Row(EqualizerCommand.SetBand(10, Millibels(300)), defaults),
            Row(
                EqualizerCommand.UsePreset(EqualizerPreset.ROCK),
                defaults.copy(equalizer = equalizer.copy(bands = EqualizerPreset.ROCK.levels, preset = EqualizerPreset.ROCK)),
            ),
            Row(EqualizerCommand.SetBassBoost(400), defaults.copy(equalizer = equalizer.copy(bassBoost = 400))),
            Row(EqualizerCommand.SetBassBoost(-4), defaults),
            Row(EqualizerCommand.SetBassBoost(5_000), defaults.copy(equalizer = equalizer.copy(bassBoost = 1_000))),
            Row(EqualizerCommand.SetLoudness(Millibels(250)), defaults.copy(equalizer = equalizer.copy(loudness = Millibels(250)))),
            Row(EqualizerCommand.SetLoudness(Millibels(-250)), defaults),
            Row(EqualizerCommand.SetLoudness(Millibels(2_500)), defaults.copy(equalizer = equalizer.copy(loudness = Millibels(1_000)))),
        )

    private val analysisRows: List<Row> =
        listOf(
            Row(AnalysisCommand.SetBeatSensitivity(4f), defaults.copy(analysis = analysis.copy(beatSensitivity = 4f))),
            Row(AnalysisCommand.SetBeatSensitivity(0f), defaults.copy(analysis = analysis.copy(beatSensitivity = 1.5f))),
            Row(AnalysisCommand.SetBeatSensitivity(20f), defaults.copy(analysis = analysis.copy(beatSensitivity = 8f))),
            Row(AnalysisCommand.SetBeatMinInterval(500f), defaults.copy(analysis = analysis.copy(beatMinIntervalMs = 500f))),
            Row(AnalysisCommand.SetBeatMinInterval(10f), defaults.copy(analysis = analysis.copy(beatMinIntervalMs = 200f))),
            Row(AnalysisCommand.SetBeatMinInterval(5_000f), defaults.copy(analysis = analysis.copy(beatMinIntervalMs = 1_200f))),
            Row(
                AnalysisCommand.UseBeatTuning(BeatTuning.SLOW_TRACK),
                defaults.copy(analysis = analysis.copy(beatSensitivity = 5.5f, beatMinIntervalMs = 700f)),
            ),
            Row(AnalysisCommand.UseBeatTuning(BeatTuning.DEFAULT), defaults),
            Row(AnalysisCommand.SetPresetMorph(Seconds(4f)), defaults.copy(analysis = analysis.copy(presetMorph = Seconds(4f)))),
            Row(AnalysisCommand.SetPresetMorph(Seconds(-1f)), defaults.copy(analysis = analysis.copy(presetMorph = Seconds(0f)))),
            Row(AnalysisCommand.SetPresetMorph(Seconds(30f)), defaults.copy(analysis = analysis.copy(presetMorph = Seconds(8f)))),
            Row(AnalysisCommand.SetReactToMicrophone(true), defaults.copy(analysis = analysis.copy(reactToMicrophone = true))),
        )

    private val behaviorRows: List<Row> =
        listOf(
            Row(BehaviorCommand.SetTouchSmear(true), defaults.copy(behavior = behavior.copy(touchSmear = true))),
            Row(BehaviorCommand.SetTouchSmearStrength(1.5f), defaults.copy(behavior = behavior.copy(touchSmearStrength = 1.5f))),
            Row(BehaviorCommand.SetTouchSmearStrength(0f), defaults.copy(behavior = behavior.copy(touchSmearStrength = 0.2f))),
            Row(BehaviorCommand.SetTouchSmearStrength(9f), defaults.copy(behavior = behavior.copy(touchSmearStrength = 2f))),
            Row(BehaviorCommand.SetTouchTransform(false), defaults.copy(behavior = behavior.copy(touchTransform = false))),
            Row(BehaviorCommand.SetReducedMotion(true), defaults.copy(behavior = behavior.copy(reducedMotion = true))),
        )

    private val exportRows: List<Row> =
        listOf(
            Row(ExportCommand.SetQuality(ExportQuality.UHD4K), defaults.copy(export = defaults.export.copy(quality = ExportQuality.UHD4K))),
            Row(
                ExportCommand.SetFrameRate(ExportFrameRate.FPS30),
                defaults.copy(export = defaults.export.copy(frameRate = ExportFrameRate.FPS30)),
            ),
            Row(ExportCommand.SetRatio(ExportRatio.R9_16), defaults.copy(export = defaults.export.copy(ratio = ExportRatio.R9_16))),
            Row(ExportCommand.SetLoopSafe(true), defaults.copy(export = defaults.export.copy(loopSafe = true))),
            Row(ExportCommand.SetCodec(ExportCodec.HEVC), defaults.copy(export = defaults.export.copy(codec = ExportCodec.HEVC))),
            Row(
                ExportCommand.SetLoudness(ExportLoudness.YOUTUBE),
                defaults.copy(export = defaults.export.copy(loudness = ExportLoudness.YOUTUBE)),
            ),
            Row(ExportCommand.SetQuality(ExportQuality.SD480), defaults.copy(export = defaults.export.copy(quality = ExportQuality.SD480))),
            Row(
                ExportCommand.SetQuality(ExportQuality.QHD1440),
                defaults.copy(export = defaults.export.copy(quality = ExportQuality.QHD1440)),
            ),
            Row(ExportCommand.SetFinishedAlerts(false), defaults.copy(export = defaults.export.copy(finishedAlerts = false))),
        )

    private val backgroundRows: List<Row> =
        listOf(
            Row(BackgroundCommand.SetMusic(false), defaults.copy(background = background.copy(music = false))),
            Row(BackgroundCommand.SetRenders(false), defaults.copy(background = background.copy(renders = false))),
            Row(BackgroundCommand.SetRecording(true), defaults.copy(background = background.copy(recording = true))),
        )

    private val performanceRows: List<Row> =
        listOf(
            Row(
                PerformanceCommand.SetVisualQuality(VisualQuality.BALANCED),
                defaults.copy(performance = performance.copy(visualQuality = VisualQuality.BALANCED)),
            ),
            Row(
                PerformanceCommand.SetVisualFrameRate(VisualFrameRate.FPS60),
                defaults.copy(performance = performance.copy(visualFrameRate = VisualFrameRate.FPS60)),
            ),
            Row(PerformanceCommand.SetSaveMode(true), defaults.copy(performance = performance.copy(saveMode = true))),
        )

    private val folderRows: List<Row> =
        listOf(
            Row(FolderCommand.SetVideoFolder(videos), defaults.copy(folders = folders.copy(videoFolder = videos))),
            Row(FolderCommand.SetPresetMirror(music), defaults.copy(folders = folders.copy(presetMirror = music))),
            Row(FolderCommand.SetPresetMirror(null), defaults),
            Row(FolderCommand.SetPresetFolder(presets), defaults.copy(folders = folders.copy(presetFolder = presets))),
            Row(FolderCommand.SetPresetFolder(null), defaults),
            Row(FolderCommand.AddMusicFolder(music), defaults.copy(folders = folders.copy(musicFolders = setOf(music)))),
            Row(FolderCommand.RemoveMusicFolder(music), defaults),
        )

    private val onboardingRows: List<Row> =
        listOf(
            Row(OnboardingCommand.AcknowledgeSafety, defaults.copy(onboarding = onboarding.copy(safetyAcknowledged = true))),
            Row(OnboardingCommand.FinishSetup, defaults.copy(onboarding = onboarding.copy(setupDone = true))),
            Row(OnboardingCommand.FinishTutorial, defaults.copy(onboarding = onboarding.copy(tutorialSeen = true))),
            Row(OnboardingCommand.SetTutorialOnFirstRun(false), defaults.copy(onboarding = onboarding.copy(tutorialOnFirstRun = false))),
        )

    @Test
    fun `every command changes its own setting and keeps it in range`() {
        val rows =
            lookRows + playbackRows + equalizerRows + analysisRows + behaviorRows + exportRows + folderRows + onboardingRows +
                backgroundRows + performanceRows
        for (row in rows) {
            assertEquals("${row.command}", row.after, SettingsRules.apply(defaults, row.command))
        }
    }

    @Test
    fun `moving a band by hand leaves the preset, keeping the other bands`() {
        val rock = SettingsRules.apply(defaults, EqualizerCommand.UsePreset(EqualizerPreset.ROCK))

        val moved = SettingsRules.apply(rock, EqualizerCommand.SetBand(1, Millibels(-600)))

        val expected =
            EqualizerPreset.ROCK.levels
                .toMutableList()
                .also { it[1] = Millibels(-600) }
        assertEquals(equalizer.copy(bands = expected, preset = null), moved.equalizer)
    }

    @Test
    fun `music folders are added and removed one at a time`() {
        val both =
            listOf<SettingsCommand>(FolderCommand.AddMusicFolder(music), FolderCommand.AddMusicFolder(more))
                .fold(defaults, SettingsRules::apply)

        assertEquals(setOf(music, more), both.folders.musicFolders)
        assertEquals(setOf(more), SettingsRules.apply(both, FolderCommand.RemoveMusicFolder(music)).folders.musicFolders)
    }

    @Test
    fun `the preset folder and the preset mirror are set apart`() {
        val both =
            listOf<SettingsCommand>(FolderCommand.SetPresetMirror(music), FolderCommand.SetPresetFolder(presets))
                .fold(defaults, SettingsRules::apply)

        assertEquals(folders.copy(presetMirror = music, presetFolder = presets), both.folders)
        assertEquals(folders.copy(presetMirror = music), SettingsRules.apply(both, FolderCommand.SetPresetFolder(null)).folders)
        assertEquals(folders.copy(presetFolder = presets), SettingsRules.apply(both, FolderCommand.SetPresetMirror(null)).folders)
    }

    @Test
    fun `save mode leaves the chosen quality and frame rate untouched`() {
        val chosen =
            listOf<SettingsCommand>(
                PerformanceCommand.SetVisualQuality(VisualQuality.BALANCED),
                PerformanceCommand.SetVisualFrameRate(VisualFrameRate.FPS60),
                PerformanceCommand.SetSaveMode(true),
            ).fold(defaults, SettingsRules::apply)

        assertEquals(PerformanceSettings(VisualQuality.BALANCED, VisualFrameRate.FPS60, saveMode = true), chosen.performance)
        assertEquals(
            PerformanceSettings(VisualQuality.BALANCED, VisualFrameRate.FPS60, saveMode = false),
            SettingsRules.apply(chosen, PerformanceCommand.SetSaveMode(false)).performance,
        )
    }

    @Test
    fun `values read back from storage are put in range`() {
        val loud = listOf(Millibels(4_000), Millibels(-4_000))
        val wild =
            SettingsValues(
                look = look.copy(accentIntensity = 7f, backgroundDim = -2f, textScale = 0f, barOpacity = 5f),
                playback = playback.copy(speed = 9f, pitch = Semitones(-9f), sleepTimer = Minutes(-1), fade = Millis(-5)),
                equalizer = equalizer.copy(bands = loud, bassBoost = 2_000, loudness = Millibels(-1)),
                analysis = analysis.copy(beatSensitivity = 0f, beatMinIntervalMs = 9_000f, presetMorph = Seconds(99f)),
                behavior = behavior.copy(touchSmearStrength = 0f),
            )

        val tamed = SettingsRules.inRange(wild)

        assertEquals(look.copy(accentIntensity = 1.5f, backgroundDim = 0f, textScale = 0.85f, barOpacity = 1f), tamed.look)
        assertEquals(playback.copy(speed = 2f, pitch = Semitones(-6f), sleepTimer = Minutes(0), fade = Millis.ZERO), tamed.playback)
        assertEquals(equalizer.copy(bands = bands(0 to 1_500, 1 to -1_500), bassBoost = 1_000), tamed.equalizer)
        assertEquals(analysis.copy(beatSensitivity = 1.5f, beatMinIntervalMs = 1_200f, presetMorph = Seconds(8f)), tamed.analysis)
        assertEquals(behavior.copy(touchSmearStrength = 0.2f), tamed.behavior)
    }

    @Test
    fun `a chosen preset decides the bands`() {
        val stale = SettingsValues(equalizer = equalizer.copy(bands = bands(0 to 900), preset = EqualizerPreset.BASS))

        assertEquals(EqualizerPreset.BASS.levels, SettingsRules.inRange(stale).equalizer.bands)
    }

    @Test
    fun `settings in range are left as they are`() {
        val custom =
            SettingsValues(
                look = look.copy(accentIntensity = 0.5f, textScale = 1.3f),
                equalizer = equalizer.copy(bands = bands(3 to 1_500)),
            )

        assertEquals(custom, SettingsRules.inRange(custom))
    }

    private fun bands(vararg set: Pair<Int, Int>): List<Millibels> {
        val levels = MutableList(EqualizerSettings.BANDS) { Millibels(0) }
        for ((band, level) in set) levels[band] = Millibels(level)
        return levels
    }

    private class Row(
        val command: SettingsCommand,
        val after: SettingsValues,
    )
}
