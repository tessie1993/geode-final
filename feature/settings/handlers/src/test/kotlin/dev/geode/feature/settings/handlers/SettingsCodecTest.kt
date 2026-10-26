package dev.geode.feature.settings.handlers

import dev.geode.engine.types.Millis
import dev.geode.feature.settings.api.AmbientMode
import dev.geode.feature.settings.api.AnalysisSettings
import dev.geode.feature.settings.api.Argb
import dev.geode.feature.settings.api.BackgroundSettings
import dev.geode.feature.settings.api.BehaviorSettings
import dev.geode.feature.settings.api.CornerStyle
import dev.geode.feature.settings.api.CrossfadeCurve
import dev.geode.feature.settings.api.Decibels
import dev.geode.feature.settings.api.Element
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
import dev.geode.feature.settings.api.PerformanceSettings
import dev.geode.feature.settings.api.PlaybackSettings
import dev.geode.feature.settings.api.PlayerPosition
import dev.geode.feature.settings.api.ReplayGainMode
import dev.geode.feature.settings.api.Seconds
import dev.geode.feature.settings.api.Semitones
import dev.geode.feature.settings.api.SettingsValues
import dev.geode.feature.settings.api.VisualFrameRate
import dev.geode.feature.settings.api.VisualQuality
import dev.geode.feature.settings.api.VisualSurface
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
            "gui_accent_intensity" to 1.25f,
            "gui_background_dim" to 0.3f,
            "gui_font_color" to 0xFFD6B15A.toInt(),
            "gui_text_scale" to 1.15f,
            "gui_opacity" to 0.5f,
            "gui_player_pos" to "TOP",
            "gui_corner" to "PILL",
            "gui_compact_player" to true,
            "gui_clear_visuals_menu" to true,
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
            "gui_mic_reactive" to true,
            "gui_touch_smear" to true,
            "gui_touch_smear_strength" to 1.5f,
            "gui_touch_transform" to false,
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
                    accentIntensity = 1.25f,
                    backgroundDim = 0.3f,
                    fontColor = Argb(0xFFD6B15A.toInt()),
                    textScale = 1.15f,
                    barOpacity = 0.5f,
                    playerPosition = PlayerPosition.TOP,
                    cornerStyle = CornerStyle.PILL,
                    compactPlayer = true,
                    clearVisualsMenu = true,
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
                    reactToMicrophone = true,
                ),
            behavior =
                BehaviorSettings(
                    touchSmear = true,
                    touchSmearStrength = 1.5f,
                    touchTransform = false,
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

    /**
     * The keys written since the old files, each at its default: whatever the codec writes beyond
     * [today]. A new setting joins this by itself; its own tests pin its name and format.
     */
    private val newKeysAtDefaults: Map<String, Any?> = SettingsCodec.encode(SettingsValues()) - today.keys

    /** #28's and #196's preferences, every one away from its default. */
    private val preferences: Map<String, Any?> =
        mapOf(
            "export_quality" to "QHD1440",
            "export_finished_alerts" to false,
            "background_music" to false,
            "background_renders" to false,
            "background_recording" to true,
            "visual_quality" to "BALANCED",
            "visual_frame_rate" to "FPS30",
            "cpu_gpu_save_mode" to true,
            "visuals_off" to true,
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
        assertEquals(today + newKeysAtDefaults, SettingsCodec.encode(todayValues))
    }

    @Test
    fun `the keys written since the old files are in no old file`() {
        assertTrue(newKeysAtDefaults.isNotEmpty())
        assertTrue(newKeysAtDefaults.keys.none { key -> SettingsKeys.LEGACY_FILES.values.any { key in it } })
    }

    @Test
    fun `settings stored before the preset folder load unchanged and write back as they were`() {
        val loaded = SettingsCodec.decode(today)

        assertEquals(null, loaded.folders.presetFolder)
        assertEquals(today, SettingsCodec.encode(loaded).filterKeys { it in today })
        assertEquals(newKeysAtDefaults, SettingsCodec.encode(loaded) - today.keys)
    }

    @Test
    fun `the preset folder is kept in its own key, apart from the preset mirror`() {
        val stored = today + newKeysAtDefaults + ("preset_folder_uri" to "content://tree/geode-presets")

        val values = SettingsCodec.decode(stored)

        assertEquals(FolderUri("content://tree/geode-presets"), values.folders.presetFolder)
        assertEquals(FolderUri("content://tree/presets"), values.folders.presetMirror)
        assertEquals(stored, SettingsCodec.encode(values))
    }

    @Test
    fun `the run in background, CPU GPU and export alert preferences are read and written in their own keys`() {
        val stored = today + newKeysAtDefaults + preferences

        val values = SettingsCodec.decode(stored)

        assertEquals(BackgroundSettings(music = false, renders = false, recording = true), values.background)
        assertEquals(
            PerformanceSettings(VisualQuality.BALANCED, VisualFrameRate.FPS30, saveMode = true, visualsOff = true),
            values.performance,
        )
        assertFalse(values.export.finishedAlerts)
        assertEquals(ExportQuality.QHD1440, values.export.quality)
        assertEquals(stored, SettingsCodec.encode(values))
    }

    @Test
    fun `a surface's own quality and frame rate are kept by name in keys of their own`() {
        val stored =
            today + newKeysAtDefaults +
                mapOf(
                    "visual_quality_wallpaper" to "SAVER",
                    "visual_frame_rate_wallpaper" to "FPS30",
                    "visual_quality_pip" to "BALANCED",
                    "visual_frame_rate_screen_saver" to "FPS60",
                    "visual_quality_visual" to "HIGH",
                    "visual_frame_rate_visual" to "SCREEN_MAX",
                )

        val values = SettingsCodec.decode(stored)

        assertEquals(
            mapOf(
                VisualSurface.WALLPAPER to VisualQuality.SAVER,
                VisualSurface.PICTURE_IN_PICTURE to VisualQuality.BALANCED,
                VisualSurface.VISUAL to VisualQuality.HIGH,
            ),
            values.performance.surfaceQuality,
        )
        assertEquals(
            mapOf(
                VisualSurface.WALLPAPER to VisualFrameRate.FPS30,
                VisualSurface.SCREEN_SAVER to VisualFrameRate.FPS60,
                VisualSurface.VISUAL to VisualFrameRate.SCREEN_MAX,
            ),
            values.performance.surfaceFrameRate,
        )
        assertEquals(stored, SettingsCodec.encode(values))
    }

    @Test
    fun `a surface that follows the shared setting has no key, and odd values follow it too`() {
        val stored = SettingsCodec.encode(SettingsValues())
        val surfaceKeys =
            listOf(
                "visual_quality_visual",
                "visual_quality_wallpaper",
                "visual_quality_pip",
                "visual_quality_screen_saver",
                "visual_frame_rate_visual",
                "visual_frame_rate_wallpaper",
                "visual_frame_rate_pip",
                "visual_frame_rate_screen_saver",
            )

        for (key in surfaceKeys) assertTrue(key, key in stored && stored[key] == null)
        assertTrue(SettingsKeys.LEGACY_FILES.values.none { keys -> keys.any { it in surfaceKeys } })
        val odd = SettingsCodec.decode(mapOf("visual_quality_wallpaper" to "ULTRA", "visual_frame_rate_pip" to 30))
        assertEquals(PerformanceSettings(), odd.performance)
    }

    @Test
    fun `every export size is stored by its name`() {
        val names = ExportQuality.entries.map { idOf(it) }

        assertEquals(listOf("SD480", "HD720", "FHD1080", "QHD1440", "UHD4K"), names)
        assertEquals(ExportQuality.SD480, SettingsCodec.decode(mapOf("export_quality" to "SD480")).export.quality)
    }

    @Test
    fun `the new preferences are in no old file, and odd values read as their defaults`() {
        assertTrue(SettingsKeys.LEGACY_FILES.values.none { keys -> keys.any { it in preferences.keys - "export_quality" } })

        val odd =
            SettingsCodec.decode(
                mapOf("visual_quality" to "ULTRA", "visual_frame_rate" to 144, "cpu_gpu_save_mode" to "yes", "background_music" to 0),
            )

        assertEquals(PerformanceSettings(), odd.performance)
        assertEquals(BackgroundSettings(), odd.background)
    }

    @Test
    fun `the preset folder is new, so it is read from no old file`() {
        assertTrue(SettingsKeys.LEGACY_FILES.values.none { "preset_folder_uri" in it })
        assertEquals(null, SettingsCodec.decode(mapOf("preset_folder_uri" to 7)).folders.presetFolder)
    }

    @Test
    fun `the video save folder is kept in its own key and read back unchanged`() {
        val stored = today + newKeysAtDefaults + ("video_folder_uri" to "content://tree/geode-videos")

        val values = SettingsCodec.decode(stored)

        assertEquals(FolderUri("content://tree/geode-videos"), values.folders.videoFolder)
        assertEquals(null, values.folders.presetFolder)
        assertEquals(stored, SettingsCodec.encode(values))
    }

    @Test
    fun `the video save folder is new, so it is read from no old file and starts unpicked`() {
        assertTrue(SettingsKeys.LEGACY_FILES.values.none { "video_folder_uri" in it })
        assertEquals(null, SettingsCodec.decode(today).folders.videoFolder)
        assertEquals(null, SettingsCodec.decode(mapOf("video_folder_uri" to 7)).folders.videoFolder)
    }

    @Test
    fun `the ambient mode is kept by name and read back`() {
        for (mode in AmbientMode.entries) {
            val stored = SettingsCodec.encode(SettingsValues(look = LookSettings(ambientMode = mode)))

            assertEquals(mode.name, stored["gui_ambient_mode"])
            assertEquals(mode, SettingsCodec.decode(stored).look.ambientMode)
        }
    }

    @Test
    fun `the Element is kept by name and read back`() {
        for (element in Element.entries) {
            val stored = SettingsCodec.encode(SettingsValues(look = LookSettings(element = element)))

            assertEquals(element.name, stored["gui_element"])
            assertEquals(element, SettingsCodec.decode(stored).look.element)
        }
    }

    @Test
    fun `a missing or unknown ambient mode loads as Drifting`() {
        assertEquals(AmbientMode.DRIFTING, SettingsCodec.decode(today).look.ambientMode)
        assertEquals(AmbientMode.DRIFTING, SettingsCodec.decode(mapOf("gui_ambient_mode" to "Wobbling")).look.ambientMode)
        assertEquals(AmbientMode.DRIFTING, SettingsCodec.decode(mapOf("gui_ambient_mode" to 2)).look.ambientMode)
        assertEquals("DRIFTING", SettingsCodec.encode(SettingsValues())["gui_ambient_mode"])
    }

    @Test
    fun `the ambient mode is new, so it is read from no old file`() {
        assertTrue(SettingsKeys.LEGACY_FILES.values.none { "gui_ambient_mode" in it })
    }

    @Test
    fun `a forest theme id loads as the Element that replaced it`() {
        fun loaded(id: String) = SettingsCodec.decode(mapOf("gui_element" to id)).look.element

        assertEquals(Element.WOOD, loaded("DeepForest"))
        assertEquals(Element.BIOLUMINESCENCE, loaded("NightForest"))
        assertEquals(Element.STONE, loaded("EarthLichen"))
    }

    @Test
    fun `a missing or unknown Element loads as Water`() {
        assertEquals(Element.WATER, SettingsCodec.decode(today).look.element)
        assertEquals(Element.WATER, SettingsCodec.decode(mapOf("gui_element" to "Lapis")).look.element)
        assertEquals(Element.WATER, SettingsCodec.decode(mapOf("gui_element" to 2)).look.element)
    }

    @Test
    fun `the Element is new, so it is read from no old file`() {
        assertTrue(SettingsKeys.LEGACY_FILES.values.none { "gui_element" in it })
    }

    @Test
    fun `the defaults are written in today's formats too`() {
        val stored = SettingsCodec.encode(SettingsValues())

        assertEquals(1.0f, stored["gui_text_scale"])
        assertEquals("WATER", stored["gui_element"])
        assertEquals("0,0,0,0,0,0,0,0,0,0", stored["band_levels"])
        assertEquals(-1, stored["preset"])
        assertEquals(60, stored["export_fps"])
        assertEquals("as-is", stored["export_loudness_target"])
        assertEquals(1, stored["crossfade_curve"])
        assertEquals(emptySet<String>(), stored["roots"])
        assertTrue("gui_font_color" in stored && stored["gui_font_color"] == null)
        assertTrue("preset_mirror_uri" in stored && stored["preset_mirror_uri"] == null)
        assertTrue("preset_folder_uri" in stored && stored["preset_folder_uri"] == null)
        assertTrue("video_folder_uri" in stored && stored["video_folder_uri"] == null)
        assertEquals(SettingsValues(), SettingsCodec.decode(stored))
    }

    @Test
    fun `what older builds stored is understood`() {
        val older =
            mapOf(
                "gui_white_font" to true,
                "morph_beats" to 6,
                "gui_safety_choice" to "REDUCED_MOTION",
                "gui_intent" to "LISTEN",
            )

        val values = SettingsCodec.decode(older)

        assertEquals(Argb(0xFFFFFFFF.toInt()), values.look.fontColor)
        assertEquals(Seconds(3f), values.analysis.presetMorph)
        assertTrue(values.behavior.reducedMotion)
        assertTrue(values.onboarding.safetyAcknowledged)
        assertTrue(values.onboarding.setupDone)
    }

    @Test
    fun `the other old answers carry over only what they meant`() {
        val values = SettingsCodec.decode(mapOf("gui_white_font" to false, "gui_safety_choice" to "CALM"))

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
    fun `settings retired before this feature are not read`() {
        val retired = listOf("app_theme", "gui_follow_system_dark", "boot_anim", "gui_key_color", "gui_second_screen", "bit_perfect")

        assertTrue(retired.none { key -> SettingsKeys.LEGACY_FILES.values.any { key in it } })
        assertTrue(retired.none { it in SettingsCodec.encode(SettingsValues()) })
    }

    @Test
    fun `every key is a setting of one old file only`() {
        val all = SettingsKeys.LEGACY_FILES.values.flatten()

        assertEquals(all.size, all.toSet().size)
        assertTrue(today.keys.all { it in all })
        assertEquals(setOf("geode-prefs", "geode-player", "geode-audiofx", "geode-library"), SettingsKeys.LEGACY_FILES.keys)
    }

    private fun idOf(quality: ExportQuality): Any? =
        SettingsCodec.encode(SettingsValues(export = ExportSettings(quality = quality)))["export_quality"]

    private fun idOf(loudness: ExportLoudness): Any? =
        SettingsCodec.encode(SettingsValues(export = ExportSettings(loudness = loudness)))["export_loudness_target"]
}
