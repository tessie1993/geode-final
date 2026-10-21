package dev.geode.feature.settings.handlers

import dev.geode.engine.types.Millis
import dev.geode.feature.settings.api.AnalysisSettings
import dev.geode.feature.settings.api.Argb
import dev.geode.feature.settings.api.BehaviorSettings
import dev.geode.feature.settings.api.CornerStyle
import dev.geode.feature.settings.api.CrossfadeCurve
import dev.geode.feature.settings.api.Decibels
import dev.geode.feature.settings.api.EqualizerPreset
import dev.geode.feature.settings.api.EqualizerSettings
import dev.geode.feature.settings.api.ExportCodec
import dev.geode.feature.settings.api.ExportFrameRate
import dev.geode.feature.settings.api.ExportLoudness
import dev.geode.feature.settings.api.ExportQuality
import dev.geode.feature.settings.api.ExportRatio
import dev.geode.feature.settings.api.ExportSettings
import dev.geode.feature.settings.api.FolderSettings
import dev.geode.feature.settings.api.FolderUri
import dev.geode.feature.settings.api.LookSettings
import dev.geode.feature.settings.api.Millibels
import dev.geode.feature.settings.api.Minutes
import dev.geode.feature.settings.api.OnboardingSettings
import dev.geode.feature.settings.api.PlaybackSettings
import dev.geode.feature.settings.api.PlayerPosition
import dev.geode.feature.settings.api.ReplayGainMode
import dev.geode.feature.settings.api.Seconds
import dev.geode.feature.settings.api.Semitones
import dev.geode.feature.settings.api.SettingsValues
import dev.geode.feature.settings.api.ThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stored form of the settings: today's keys and formats, read back exactly, and what older
 * builds stored understood. The maps here are what SharedPreferences.getAll() and DataStore hand over.
 */
class SettingsCodecTest {
    /** Every setting away from its default, as today's build stores it across its four files. */
    private val today: Map<String, Any?> =
        mapOf(
            "app_theme" to "onyx",
            "gui_accent_intensity" to 1.25f,
            "gui_background_dim" to 0.3f,
            "gui_follow_system_dark" to true,
            "gui_font_color" to 0xFFD6B15A.toInt(),
            "gui_text_scale" to 1.15f,
            "gui_opacity" to 0.5f,
            "gui_player_pos" to "TOP",
            "gui_corner" to "PILL",
            "gui_compact_player" to true,
            "gui_clear_visuals_menu" to true,
            "boot_anim" to false,
            "speed" to 1.25f,
            "pitch_semitones" to -2f,
            "skip_silence" to true,
            "pause_on_noisy" to false,
            "keep_screen_on" to true,
            "auto_resume" to true,
            "resume_long_tracks" to false,
            "sleep_timer_minutes" to 45,
            "sleep_finish_track" to true,
            "fade_ms" to 1_500,
            "replaygain_mode" to 2,
            "replaygain_preamp_db" to -3.5f,
            "replaygain_clip_guard" to false,
            "native_engine" to true,
            "gapless" to false,
            "bit_perfect" to true,
            "crossfade_ms" to 4_000,
            "crossfade_curve" to 2,
            "enabled" to true,
            "band_levels" to "100,200,300,400,500,-100,-200,-300,-400,-500",
            "preset" to -1,
            "bass" to 300,
            "loudness" to 150,
            "beat_sigma" to 5f,
            "beat_min_interval_ms" to 450f,
            "gui_preset_morph_seconds" to 3.5f,
            "gui_key_color" to true,
            "gui_mic_reactive" to true,
            "gui_touch_smear" to true,
            "gui_touch_smear_strength" to 1.5f,
            "gui_touch_transform" to false,
            "gui_second_screen" to false,
            "gui_reduced_motion" to true,
            "export_quality" to "UHD4K",
            "export_fps" to 30,
            "export_ratio" to "R9_16",
            "export_loop" to true,
            "export_codec" to "HEVC",
            "export_loudness_target" to "shorts",
            "preset_mirror_uri" to "content://tree/presets",
            "roots" to setOf("content://tree/music", "content://tree/more"),
            "gui_safety_acknowledged" to true,
            "gui_setup_done" to true,
            "gui_tutorial_seen" to true,
            "gui_tutorial_on_first_run" to false,
        )

    private val todayValues =
        SettingsValues(
            look =
                LookSettings(
                    theme = ThemeId("onyx"),
                    accentIntensity = 1.25f,
                    backgroundDim = 0.3f,
                    followSystemDark = true,
                    fontColor = Argb(0xFFD6B15A.toInt()),
                    textScale = 1.15f,
                    barOpacity = 0.5f,
                    playerPosition = PlayerPosition.TOP,
                    cornerStyle = CornerStyle.PILL,
                    compactPlayer = true,
                    clearVisualsMenu = true,
                    bootAnimation = false,
                ),
            playback =
                PlaybackSettings(
                    speed = 1.25f,
                    pitch = Semitones(-2f),
                    skipSilence = true,
                    pauseWhenUnplugged = false,
                    keepScreenOn = true,
                    autoResume = true,
                    resumeLongTracks = false,
                    sleepTimer = Minutes(45),
                    sleepFinishTrack = true,
                    fade = Millis(1_500),
                    replayGain = ReplayGainMode.ALBUM,
                    replayGainPreamp = Decibels(-3.5f),
                    replayGainClipGuard = false,
                    nativeEngine = true,
                    gapless = false,
                    bitPerfect = true,
                    crossfade = Millis(4_000),
                    crossfadeCurve = CrossfadeCurve.SMOOTH,
                ),
            equalizer =
                EqualizerSettings(
                    enabled = true,
                    bands = listOf(100, 200, 300, 400, 500, -100, -200, -300, -400, -500).map(::Millibels),
                    preset = null,
                    bassBoost = 300,
                    loudness = Millibels(150),
                ),
            analysis =
                AnalysisSettings(
                    beatSensitivity = 5f,
                    beatMinIntervalMs = 450f,
                    presetMorph = Seconds(3.5f),
                    keyColour = true,
                    reactToMicrophone = true,
                ),
            behavior =
                BehaviorSettings(
                    touchSmear = true,
                    touchSmearStrength = 1.5f,
                    touchTransform = false,
                    secondScreen = false,
                    reducedMotion = true,
                ),
            export =
                ExportSettings(
                    quality = ExportQuality.UHD4K,
                    frameRate = ExportFrameRate.FPS30,
                    ratio = ExportRatio.R9_16,
                    loopSafe = true,
                    codec = ExportCodec.HEVC,
                    loudness = ExportLoudness.SHORTS_AND_TIKTOK,
                ),
            folders =
                FolderSettings(
                    presetMirror = FolderUri("content://tree/presets"),
                    musicFolders = setOf(FolderUri("content://tree/music"), FolderUri("content://tree/more")),
                ),
            onboarding =
                OnboardingSettings(
                    safetyAcknowledged = true,
                    setupDone = true,
                    tutorialSeen = true,
                    tutorialOnFirstRun = false,
                ),
        )

    @Test
    fun `nothing stored reads as the defaults`() {
        assertEquals(SettingsValues(), SettingsCodec.decode(emptyMap()))
    }

    @Test
    fun `every setting today's build stores is read unchanged`() {
        assertEquals(todayValues, SettingsCodec.decode(today))
    }

    @Test
    fun `settings are written in today's keys and formats`() {
        assertEquals(today, SettingsCodec.encode(todayValues))
    }

    @Test
    fun `the defaults are written in today's formats too`() {
        val stored = SettingsCodec.encode(SettingsValues())

        assertEquals("lapis-lazuli", stored["app_theme"])
        assertEquals("0,0,0,0,0,0,0,0,0,0", stored["band_levels"])
        assertEquals(-1, stored["preset"])
        assertEquals(60, stored["export_fps"])
        assertEquals("as-is", stored["export_loudness_target"])
        assertEquals(1, stored["crossfade_curve"])
        assertEquals(emptySet<String>(), stored["roots"])
        assertTrue("gui_font_color" in stored && stored["gui_font_color"] == null)
        assertTrue("preset_mirror_uri" in stored && stored["preset_mirror_uri"] == null)
        assertEquals(SettingsValues(), SettingsCodec.decode(stored))
    }

    @Test
    fun `what older builds stored is understood`() {
        val older =
            mapOf(
                "app_theme" to "CLEAR_QUARTZ",
                "gui_white_font" to true,
                "morph_beats" to 6,
                "gui_safety_choice" to "REDUCED_MOTION",
                "gui_intent" to "LISTEN",
            )

        val values = SettingsCodec.decode(older)

        assertEquals(ThemeId("clear-quartz"), values.look.theme)
        assertEquals(Argb(0xFFFFFFFF.toInt()), values.look.fontColor)
        assertEquals(Seconds(3f), values.analysis.presetMorph)
        assertTrue(values.behavior.reducedMotion)
        assertTrue(values.onboarding.safetyAcknowledged)
        assertTrue(values.onboarding.setupDone)
    }

    @Test
    fun `the other old answers carry over only what they meant`() {
        val values = SettingsCodec.decode(mapOf("app_theme" to "SUGILITE", "gui_white_font" to false, "gui_safety_choice" to "CALM"))

        assertEquals(ThemeId("sugilite"), values.look.theme)
        assertEquals(null, values.look.fontColor)
        assertFalse(values.behavior.reducedMotion)
        assertTrue(values.onboarding.safetyAcknowledged)
        assertFalse(values.onboarding.setupDone)
    }

    @Test
    fun `a chosen font colour wins over the old white font`() {
        val values = SettingsCodec.decode(mapOf("gui_font_color" to 0xFFBDEFD8.toInt(), "gui_white_font" to true))

        assertEquals(Argb(0xFFBDEFD8.toInt()), values.look.fontColor)
    }

    @Test
    fun `the morph length in seconds wins over the old beat count`() {
        assertEquals(Seconds(1f), SettingsCodec.decode(mapOf("gui_preset_morph_seconds" to 1f, "morph_beats" to 8)).analysis.presetMorph)
        assertEquals(Seconds(4f), SettingsCodec.decode(mapOf("morph_beats" to 8)).analysis.presetMorph)
    }

    @Test
    fun `odd stored values read as the nearest allowed`() {
        val odd =
            mapOf(
                "gui_player_pos" to "SIDEWAYS",
                "export_quality" to "8K",
                "export_fps" to 45,
                "export_loudness_target" to "radio",
                "replaygain_mode" to 7,
                "crossfade_curve" to -3,
                "preset" to 42,
                "band_levels" to "300, x ,9000",
                "speed" to 5f,
                "gui_opacity" to "0.5",
            )

        val values = SettingsCodec.decode(odd)

        assertEquals(PlayerPosition.BOTTOM, values.look.playerPosition)
        assertEquals(LookSettings.BAR_OPACITY_DEFAULT, values.look.barOpacity)
        assertEquals(ExportQuality.FHD1080, values.export.quality)
        assertEquals(ExportFrameRate.FPS60, values.export.frameRate)
        assertEquals(ExportLoudness.LEAVE_AS_IS, values.export.loudness)
        assertEquals(ReplayGainMode.ALBUM, values.playback.replayGain)
        assertEquals(CrossfadeCurve.LINEAR, values.playback.crossfadeCurve)
        assertEquals(null, values.equalizer.preset)
        assertEquals(listOf(300, 1_500, 0, 0, 0, 0, 0, 0, 0, 0).map(::Millibels), values.equalizer.bands)
        assertEquals(2f, values.playback.speed)
    }

    @Test
    fun `a chosen equalizer preset gives its own levels`() {
        val values = SettingsCodec.decode(mapOf("preset" to 4, "band_levels" to "1,2,3"))

        assertEquals(EqualizerPreset.ROCK, values.equalizer.preset)
        assertEquals(EqualizerPreset.ROCK.levels, values.equalizer.bands)
        assertEquals(4, SettingsCodec.encode(values)["preset"])
    }

    @Test
    fun `every export loudness is stored by its id`() {
        val ids = ExportLoudness.entries.associateWith { idOf(it) }

        assertEquals(
            mapOf(ExportLoudness.YOUTUBE to "youtube", ExportLoudness.SHORTS_AND_TIKTOK to "shorts", ExportLoudness.LEAVE_AS_IS to "as-is"),
            ids,
        )
        assertEquals(ExportLoudness.YOUTUBE, SettingsCodec.decode(mapOf("export_loudness_target" to "youtube")).export.loudness)
    }

    @Test
    fun `every key is a setting of one old file only`() {
        val all = SettingsKeys.LEGACY_FILES.values.flatten()

        assertEquals(all.size, all.toSet().size)
        assertTrue(today.keys.all { it in all })
        assertEquals(setOf("geode-prefs", "geode-player", "geode-audiofx", "geode-library"), SettingsKeys.LEGACY_FILES.keys)
    }

    private fun idOf(loudness: ExportLoudness): Any? =
        SettingsCodec.encode(SettingsValues(export = ExportSettings(loudness = loudness)))["export_loudness_target"]
}
