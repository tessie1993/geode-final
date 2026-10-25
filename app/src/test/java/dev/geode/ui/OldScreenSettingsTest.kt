package dev.geode.ui

import dev.geode.audio.dsp.DspSettings
import dev.geode.data.ExportDefaults
import dev.geode.data.PlayerPrefs
import dev.geode.engine.types.Millis
import dev.geode.export.LoudnessTarget
import dev.geode.feature.playback.api.ListeningOptions
import dev.geode.feature.playback.api.RepeatMode
import dev.geode.feature.settings.api.AnalysisCommand
import dev.geode.feature.settings.api.AnalysisSettings
import dev.geode.feature.settings.api.Argb
import dev.geode.feature.settings.api.BehaviorCommand
import dev.geode.feature.settings.api.BehaviorSettings
import dev.geode.feature.settings.api.CrossfadeCurve
import dev.geode.feature.settings.api.Decibels
import dev.geode.feature.settings.api.EqualizerPreset
import dev.geode.feature.settings.api.EqualizerSettings
import dev.geode.feature.settings.api.ExportCommand
import dev.geode.feature.settings.api.ExportFrameRate
import dev.geode.feature.settings.api.ExportLoudness
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
import dev.geode.feature.settings.api.PlaybackSettings
import dev.geode.feature.settings.api.ReplayGainMode
import dev.geode.feature.settings.api.Seconds
import dev.geode.feature.settings.api.Semitones
import dev.geode.feature.settings.api.SettingsValues
import org.junit.Assert.assertEquals
import org.junit.Test
import dev.geode.export.ExportCodec as OldCodec
import dev.geode.export.ExportQuality as OldQuality
import dev.geode.export.ExportRatio as OldRatio
import dev.geode.feature.settings.api.CornerStyle as SettingsCorner
import dev.geode.feature.settings.api.ExportCodec as SettingsCodec
import dev.geode.feature.settings.api.ExportQuality as SettingsQuality
import dev.geode.feature.settings.api.ExportRatio as SettingsRatio
import dev.geode.feature.settings.api.PlaybackCommand as SettingsPlayback
import dev.geode.feature.settings.api.PlayerPosition as SettingsPosition

/** The old screens read Settings' values, and their saves become one Settings Command per change (#17). */
class OldScreenSettingsTest {
    private val values =
        SettingsValues(
            look =
                LookSettings(
                    accentIntensity = 1.2f,
                    backgroundDim = 0.3f,
                    fontColor = Argb(0xFFFF0000.toInt()),
                    textScale = 1.1f,
                    barOpacity = 0.5f,
                    playerPosition = SettingsPosition.TOP,
                    cornerStyle = SettingsCorner.PILL,
                    compactPlayer = true,
                    clearVisualsMenu = true,
                ),
            analysis =
                AnalysisSettings(
                    beatSensitivity = 4f,
                    beatMinIntervalMs = 500f,
                    presetMorph = Seconds(3f),
                    reactToMicrophone = true,
                ),
            behavior = BehaviorSettings(touchSmear = true, touchSmearStrength = 1.5f, touchTransform = false, reducedMotion = true),
            folders = FolderSettings(presetMirror = FolderUri("content://tree/mirror")),
            onboarding = OnboardingSettings(safetyAcknowledged = true, setupDone = true, tutorialSeen = false, tutorialOnFirstRun = false),
        )

    @Test
    fun `every App UI setting reads from Settings`() {
        val gui = OldScreenSettings.guiPrefsOf(values)

        assertEquals(PlayerPosition.TOP, gui.playerPosition)
        assertEquals(CornerStyle.PILL, gui.cornerStyle)
        assertEquals(0.5f, gui.barOpacity)
        assertEquals(1.2f, gui.accentIntensity)
        assertEquals(0.3f, gui.backgroundDim)
        assertEquals(0xFFFF0000.toInt(), gui.fontColorOverride)
        assertEquals(1.1f, gui.textScale)
        assertEquals(true, gui.compactPlayer)
        assertEquals(true, gui.clearVisualsMenu)
        assertEquals(4f, gui.beatSensitivity)
        assertEquals(500f, gui.beatMinIntervalMs)
        assertEquals(3f, gui.presetMorphSeconds)
        assertEquals(true, gui.micReactive)
        assertEquals("content://tree/mirror", gui.presetMirrorUri)
        assertEquals(true, gui.touchSmear)
        assertEquals(1.5f, gui.touchSmearStrength)
        assertEquals(false, gui.touchTransform)
        assertEquals(true, gui.reducedMotion)
        assertEquals(true, gui.safetyAcknowledged)
        assertEquals(true, gui.setupDone)
        assertEquals(false, gui.tutorialSeen)
        assertEquals(false, gui.tutorialOnFirstRun)
    }

    @Test
    fun `saving unchanged App UI settings sends nothing`() {
        val gui = OldScreenSettings.guiPrefsOf(values)

        assertEquals(emptyList<Any>(), OldScreenSettings.commandsFor(gui, gui))
    }

    @Test
    fun `each changed App UI setting is one Command`() {
        val before = GuiPrefs()
        val after =
            before.copy(
                playerPosition = PlayerPosition.TOP,
                barOpacity = 0.4f,
                fontColorArgb = 0x11223344,
                presetMorphSeconds = 5f,
                micReactive = true,
                presetMirrorUri = "content://tree/x",
                reducedMotion = true,
                tutorialOnFirstRun = false,
            )

        assertEquals(
            listOf(
                LookCommand.SetPlayerPosition(SettingsPosition.TOP),
                LookCommand.SetBarOpacity(0.4f),
                LookCommand.SetFontColor(Argb(0x11223344)),
                AnalysisCommand.SetPresetMorph(Seconds(5f)),
                AnalysisCommand.SetReactToMicrophone(true),
                FolderCommand.SetPresetMirror(FolderUri("content://tree/x")),
                OnboardingCommand.SetTutorialOnFirstRun(false),
            ).toSet(),
            OldScreenSettings.commandsFor(before, after).filterNot { it is BehaviorCommand }.toSet(),
        )
        assertEquals(
            listOf(BehaviorCommand.SetReducedMotion(true)),
            OldScreenSettings.commandsFor(before, after).filterIsInstance<BehaviorCommand>(),
        )
    }

    @Test
    fun `what the person has been through is marked once and never undone`() {
        val fresh = GuiPrefs()
        val through = fresh.copy(safetyAcknowledged = true, setupDone = true, tutorialSeen = true)

        assertEquals(
            listOf(OnboardingCommand.AcknowledgeSafety, OnboardingCommand.FinishSetup, OnboardingCommand.FinishTutorial),
            OldScreenSettings.commandsFor(fresh, through),
        )
        assertEquals(emptyList<Any>(), OldScreenSettings.commandsFor(through, fresh))
    }

    @Test
    fun `the old player settings read from Settings, and shuffle and repeat from Playback`() {
        val playback =
            PlaybackSettings(
                speed = 1.5f,
                pitch = Semitones(-2f),
                skipSilence = true,
                pauseWhenUnplugged = false,
                keepScreenOn = true,
                autoResume = true,
                resumeLongTracks = false,
                sleepTimer = Minutes(30),
                sleepFinishTrack = true,
                fade = Millis(800),
                replayGain = ReplayGainMode.ALBUM,
                replayGainPreamp = Decibels(3f),
                replayGainClipGuard = false,
                nativeEngine = true,
                gapless = false,
                crossfade = Millis(4_000),
                crossfadeCurve = CrossfadeCurve.SMOOTH,
            )

        val prefs = OldScreenSettings.playerPrefsOf(playback, ListeningOptions(shuffle = true, repeat = RepeatMode.ALL))

        assertEquals(
            PlayerPrefs(
                shuffle = true,
                repeatMode = 2,
                speed = 1.5f,
                pitchSemitones = -2f,
                skipSilence = true,
                pauseOnNoisy = false,
                keepScreenOn = true,
                autoResume = true,
                sleepTimerMinutes = 30,
                sleepFinishTrack = true,
                fadeMs = 800,
                replayGainMode = 2,
                replayGainPreampDb = 3f,
                replayGainClipGuard = false,
                nativeEngine = true,
                crossfadeMs = 4_000,
                crossfadeCurve = 2,
                gapless = false,
                resumeLongTracks = false,
            ),
            prefs,
        )
    }

    @Test
    fun `each changed player setting is one Command`() {
        val before = PlayerPrefs()
        val after =
            before.copy(
                speed = 1.25f,
                fadeMs = 500,
                replayGainMode = 1,
                crossfadeMs = 2_000,
                crossfadeCurve = 0,
                nativeEngine = true,
            )

        assertEquals(
            listOf(
                SettingsPlayback.SetSpeed(1.25f),
                SettingsPlayback.SetFade(Millis(500)),
                SettingsPlayback.SetReplayGain(ReplayGainMode.TRACK),
                SettingsPlayback.SetNativeEngine(true),
                SettingsPlayback.SetCrossfade(Millis(2_000)),
                SettingsPlayback.SetCrossfadeCurve(CrossfadeCurve.LINEAR),
            ),
            OldScreenSettings.commandsFor(before, after),
        )
        assertEquals(emptyList<Any>(), OldScreenSettings.commandsFor(after, after))
    }

    @Test
    fun `the equalizer runs Settings' bands over the processor's other stages`() {
        val equalizer =
            EqualizerSettings(
                enabled = true,
                bands = EqualizerPreset.ROCK.levels,
                preset = EqualizerPreset.ROCK,
                bassBoost = 300,
                loudness = Millibels(200),
            )
        val current = DspSettings(gainDb = 2f, limiter = true)

        val dsp = OldScreenSettings.dspOf(equalizer, current)

        assertEquals(DspSettings(true, EqualizerPreset.ROCK.levels.map { it.value }, 300, 200, gainDb = 2f, limiter = true), dsp)
    }

    @Test
    fun `export defaults read from Settings and their changes are Commands`() {
        val export =
            ExportSettings(
                quality = SettingsQuality.UHD4K,
                frameRate = ExportFrameRate.FPS30,
                ratio = SettingsRatio.R9_16,
                loopSafe = true,
                codec = SettingsCodec.HEVC,
                loudness = ExportLoudness.YOUTUBE,
            )
        val defaults = OldScreenSettings.exportDefaultsOf(export)

        assertEquals(ExportDefaults(OldQuality.UHD4K, 30, OldRatio.R9_16, true, OldCodec.HEVC, LoudnessTarget.YouTube.id), defaults)
        assertEquals(emptyList<Any>(), OldScreenSettings.commandsFor(defaults, defaults))
        assertEquals(
            listOf(
                ExportCommand.SetQuality(SettingsQuality.HD720),
                ExportCommand.SetFrameRate(ExportFrameRate.FPS60),
                ExportCommand.SetLoudness(ExportLoudness.SHORTS_AND_TIKTOK),
            ),
            OldScreenSettings.commandsFor(
                defaults,
                defaults.copy(quality = OldQuality.HD720, fps = 60, loudnessTargetId = LoudnessTarget.ShortsAndTikTok.id),
            ),
        )
    }

    /** A quality neither side has yet, like one a later Settings adds. */
    private enum class NewerQuality { FHD1080, UHD8K }

    @Test
    fun `a name the other side does not know maps to nothing instead of throwing`() {
        // Settings has SD480 and QHD1440 and the old screens do not (#135).
        assertEquals(null, OldScreenSettings.byName<OldQuality>(SettingsQuality.SD480))
        assertEquals(null, OldScreenSettings.byName<OldQuality>(SettingsQuality.QHD1440))
        assertEquals(null, OldScreenSettings.byName<SettingsQuality>(NewerQuality.UHD8K))
        assertEquals(OldQuality.FHD1080, OldScreenSettings.byName<OldQuality>(NewerQuality.FHD1080))
    }

    @Test
    fun `every name both sides know maps across in both directions`() {
        OldQuality.entries.forEach { old ->
            val settings = OldScreenSettings.byName<SettingsQuality>(old)
            assertEquals(old.name, settings?.name)
            assertEquals(old, settings?.let { OldScreenSettings.byName<OldQuality>(it) })
        }
        SettingsRatio.entries.forEach { assertEquals(it.name, OldScreenSettings.byName<OldRatio>(it)?.name) }
        SettingsCodec.entries.forEach { assertEquals(it.name, OldScreenSettings.byName<OldCodec>(it)?.name) }
        SettingsPosition.entries.forEach { assertEquals(it.name, OldScreenSettings.byName<PlayerPosition>(it)?.name) }
        SettingsCorner.entries.forEach { assertEquals(it.name, OldScreenSettings.byName<CornerStyle>(it)?.name) }
    }
}
