package dev.geode.feature.settings.handlers

import dev.geode.engine.types.Millis
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.feature.settings.api.AnalysisSettings
import dev.geode.feature.settings.api.Argb
import dev.geode.feature.settings.api.BackgroundSettings
import dev.geode.feature.settings.api.BehaviorSettings
import dev.geode.feature.settings.api.CrossfadeCurve
import dev.geode.feature.settings.api.Decibels
import dev.geode.feature.settings.api.EqualizerPreset
import dev.geode.feature.settings.api.EqualizerSettings
import dev.geode.feature.settings.api.ExportFrameRate
import dev.geode.feature.settings.api.ExportLoudness
import dev.geode.feature.settings.api.ExportSettings
import dev.geode.feature.settings.api.FolderSettings
import dev.geode.feature.settings.api.FolderUri
import dev.geode.feature.settings.api.LookSettings
import dev.geode.feature.settings.api.Millibels
import dev.geode.feature.settings.api.Minutes
import dev.geode.feature.settings.api.OnboardingSettings
import dev.geode.feature.settings.api.PerformanceSettings
import dev.geode.feature.settings.api.PlaybackSettings
import dev.geode.feature.settings.api.ReplayGainMode
import dev.geode.feature.settings.api.Seconds
import dev.geode.feature.settings.api.Semitones
import dev.geode.feature.settings.api.SettingsValues
import dev.geode.feature.settings.api.StartLook
import dev.geode.feature.settings.api.TransitionLook
import dev.geode.feature.settings.api.VisualFrameRate
import dev.geode.feature.settings.api.VisualQuality
import dev.geode.feature.settings.api.VisualSurface
import dev.geode.feature.settings.core.SettingsRules
import dev.geode.feature.settings.handlers.SettingsKeys as K

/**
 * Settings to and from their stored form: a map from key name to a Boolean, Int, Float, String or
 * set of Strings, in exactly the keys and formats today's SharedPreferences files use. The same
 * [decode] reads the DataStore file and, once, the old preferences files, so it also understands
 * what older builds stored.
 */
internal object SettingsCodec {
    /** The answer to the old safety question that also meant "reduce motion". */
    private const val LEGACY_REDUCED_MOTION = "REDUCED_MOTION"

    /** What the equalizer stores when no preset is chosen. */
    private const val NO_PRESET = -1
    private const val DEFAULT_MORPH_BEATS = 4
    private const val BAND_SEPARATOR = ","
    private const val START_STYLE = "style:"
    private const val START_PRESET = "preset:"

    private val WHITE = Argb(0xFFFFFFFF.toInt())

    private val LOUDNESS_IDS =
        mapOf(
            ExportLoudness.YOUTUBE to "youtube",
            ExportLoudness.SHORTS_AND_TIKTOK to "shorts",
            ExportLoudness.LEAVE_AS_IS to "as-is",
        )

    fun decode(stored: Map<String, Any?>): SettingsValues {
        val read = Reader(stored)
        return SettingsRules.inRange(
            SettingsValues(
                look = read.look(),
                playback = read.playback(),
                equalizer = read.equalizer(),
                analysis = read.analysis(),
                behavior = read.behavior(),
                export = read.export(),
                folders = read.folders(),
                onboarding = read.onboarding(),
                background = read.background(),
                performance = read.performance(),
            ),
        )
    }

    /** Every setting by key; a null value means the key is removed. */
    fun encode(values: SettingsValues): Map<String, Any?> =
        look(values.look) + playback(values.playback) + rest(values) + preferences(values)

    private fun Reader.look(): LookSettings {
        val default = LookSettings()
        return LookSettings(
            themeMode = enum(K.THEME_MODE, default.themeMode),
            reduceTransparency = boolean(K.REDUCE_TRANSPARENCY, default.reduceTransparency),
            accentIntensity = float(K.ACCENT_INTENSITY, default.accentIntensity),
            backgroundDim = float(K.BACKGROUND_DIM, default.backgroundDim),
            fontColor = int(K.FONT_COLOR)?.let(::Argb) ?: WHITE.takeIf { boolean(K.LEGACY_WHITE_FONT, false) },
            textScale = float(K.TEXT_SCALE, default.textScale),
            barOpacity = float(K.BAR_OPACITY, default.barOpacity),
            playerPosition = enum(K.PLAYER_POSITION, default.playerPosition),
            cornerStyle = enum(K.CORNER_STYLE, default.cornerStyle),
            compactPlayer = boolean(K.COMPACT_PLAYER, default.compactPlayer),
            clearVisualsMenu = boolean(K.CLEAR_VISUALS_MENU, default.clearVisualsMenu),
        )
    }

    private fun Reader.playback(): PlaybackSettings {
        val default = PlaybackSettings()
        return PlaybackSettings(
            speed = float(K.SPEED, default.speed),
            pitch = Semitones(float(K.PITCH, default.pitch.value)),
            skipSilence = boolean(K.SKIP_SILENCE, default.skipSilence),
            pauseWhenUnplugged = boolean(K.PAUSE_WHEN_UNPLUGGED, default.pauseWhenUnplugged),
            keepScreenOn = boolean(K.KEEP_SCREEN_ON, default.keepScreenOn),
            autoResume = boolean(K.AUTO_RESUME, default.autoResume),
            resumeLongTracks = boolean(K.RESUME_LONG_TRACKS, default.resumeLongTracks),
            sleepTimer = Minutes(int(K.SLEEP_TIMER) ?: default.sleepTimer.value),
            sleepFinishTrack = boolean(K.SLEEP_FINISH_TRACK, default.sleepFinishTrack),
            fade = Millis((int(K.FADE) ?: default.fade.value.toInt()).toLong()),
            replayGain = ordinal(K.REPLAY_GAIN, ReplayGainMode.entries, default.replayGain),
            replayGainPreamp = Decibels(float(K.REPLAY_GAIN_PREAMP, default.replayGainPreamp.value)),
            replayGainClipGuard = boolean(K.REPLAY_GAIN_CLIP_GUARD, default.replayGainClipGuard),
            nativeEngine = boolean(K.NATIVE_ENGINE, default.nativeEngine),
            gapless = boolean(K.GAPLESS, default.gapless),
            crossfade = Millis((int(K.CROSSFADE) ?: default.crossfade.value.toInt()).toLong()),
            crossfadeCurve = ordinal(K.CROSSFADE_CURVE, CrossfadeCurve.entries, default.crossfadeCurve),
        )
    }

    private fun Reader.equalizer(): EqualizerSettings {
        val default = EqualizerSettings()
        val bands = string(K.EQUALIZER_BANDS)?.split(BAND_SEPARATOR)?.mapNotNull { it.trim().toIntOrNull() }
        return EqualizerSettings(
            enabled = boolean(K.EQUALIZER_ENABLED, default.enabled),
            bands = bands?.map(::Millibels) ?: default.bands,
            preset = int(K.EQUALIZER_PRESET)?.let { EqualizerPreset.entries.getOrNull(it) },
            bassBoost = int(K.BASS_BOOST) ?: default.bassBoost,
            loudness = Millibels(int(K.LOUDNESS) ?: default.loudness.value),
        )
    }

    private fun Reader.analysis(): AnalysisSettings {
        val default = AnalysisSettings()
        val morphBeats = int(K.LEGACY_MORPH_BEATS) ?: DEFAULT_MORPH_BEATS
        val legacyMorph = morphBeats * K.SECONDS_PER_MINUTE / K.LEGACY_MORPH_BPM
        return AnalysisSettings(
            beatSensitivity = float(K.BEAT_SENSITIVITY, default.beatSensitivity),
            beatMinIntervalMs = float(K.BEAT_MIN_INTERVAL, default.beatMinIntervalMs),
            presetMorph = Seconds(float(K.PRESET_MORPH, legacyMorph)),
            reactToMicrophone = boolean(K.REACT_TO_MICROPHONE, default.reactToMicrophone),
            syncOffset = Millis((int(K.SYNC_OFFSET) ?: default.syncOffset.value.toInt()).toLong()),
            sensitivity = Decibels(float(K.SENSITIVITY, default.sensitivity.value)),
        )
    }

    private fun Reader.behavior(): BehaviorSettings {
        val default = BehaviorSettings()
        return BehaviorSettings(
            touchSmear = boolean(K.TOUCH_SMEAR, default.touchSmear),
            touchSmearStrength = float(K.TOUCH_SMEAR_STRENGTH, default.touchSmearStrength),
            touchTransform = boolean(K.TOUCH_TRANSFORM, default.touchTransform),
            reducedMotion = boolean(K.REDUCED_MOTION, false) || string(K.LEGACY_SAFETY_CHOICE) == LEGACY_REDUCED_MOTION,
            tilt = enum(K.TILT, default.tilt),
            startLook = startLook(string(K.START_LOOK)),
            transitionLook = TransitionLook.ofId(string(K.TRANSITION_LOOK)) ?: default.transitionLook,
        )
    }

    /** The stored start look; anything unreadable starts on the last look. */
    private fun startLook(stored: String?): StartLook =
        when {
            stored == null -> StartLook.Last
            stored.startsWith(START_STYLE) -> StartLook.Style(SceneId(stored.removePrefix(START_STYLE)))
            stored.startsWith(START_PRESET) -> StartLook.Preset(PresetId(stored.removePrefix(START_PRESET)))
            else -> StartLook.Last
        }

    private fun startLook(look: StartLook): String? =
        when (look) {
            StartLook.Last -> null
            is StartLook.Style -> START_STYLE + look.style.value
            is StartLook.Preset -> START_PRESET + look.preset.value
        }

    private fun Reader.export(): ExportSettings {
        val default = ExportSettings()
        val loudness = string(K.EXPORT_LOUDNESS)
        return ExportSettings(
            quality = enum(K.EXPORT_QUALITY, default.quality),
            frameRate = ExportFrameRate.entries.firstOrNull { it.fps == int(K.EXPORT_FPS) } ?: default.frameRate,
            ratio = enum(K.EXPORT_RATIO, default.ratio),
            loopSafe = boolean(K.EXPORT_LOOP_SAFE, default.loopSafe),
            codec = enum(K.EXPORT_CODEC, default.codec),
            loudness = LOUDNESS_IDS.entries.firstOrNull { it.value == loudness }?.key ?: default.loudness,
            finishedAlerts = boolean(K.EXPORT_FINISHED_ALERTS, default.finishedAlerts),
            editSources = enum(K.EXPORT_EDIT_SOURCES, default.editSources),
        )
    }

    private fun Reader.background(): BackgroundSettings {
        val default = BackgroundSettings()
        return BackgroundSettings(
            music = boolean(K.BACKGROUND_MUSIC, default.music),
            renders = boolean(K.BACKGROUND_RENDERS, default.renders),
            recording = boolean(K.BACKGROUND_RECORDING, default.recording),
        )
    }

    private fun Reader.performance(): PerformanceSettings {
        val default = PerformanceSettings()
        return PerformanceSettings(
            visualQuality = enum(K.VISUAL_QUALITY, default.visualQuality),
            visualFrameRate = enum(K.VISUAL_FRAME_RATE, default.visualFrameRate),
            saveMode = boolean(K.SAVE_MODE, default.saveMode),
            visualsOff = boolean(K.VISUALS_OFF, default.visualsOff),
            surfaceQuality = bySurface { enumOrNull<VisualQuality>(K.surfaceQuality(it)) },
            surfaceFrameRate = bySurface { enumOrNull<VisualFrameRate>(K.surfaceFrameRate(it)) },
        )
    }

    /** Each surface's own value, for the surfaces that have a readable one stored. */
    private fun <T : Any> bySurface(read: (VisualSurface) -> T?): Map<VisualSurface, T> =
        VisualSurface.entries.mapNotNull { surface -> read(surface)?.let { surface to it } }.toMap()

    private fun Reader.folders(): FolderSettings =
        FolderSettings(
            videoFolder = string(K.VIDEO_FOLDER)?.let(::FolderUri),
            presetMirror = string(K.PRESET_MIRROR)?.let(::FolderUri),
            musicFolders = strings(K.MUSIC_FOLDERS).map(::FolderUri).toSet(),
            presetFolder = string(K.PRESET_FOLDER)?.let(::FolderUri),
        )

    private fun Reader.onboarding(): OnboardingSettings =
        OnboardingSettings(
            safetyAcknowledged = boolean(K.SAFETY_ACKNOWLEDGED, false) || string(K.LEGACY_SAFETY_CHOICE) != null,
            setupDone = boolean(K.SETUP_DONE, false) || string(K.LEGACY_INTENT) != null,
            tutorialSeen = boolean(K.TUTORIAL_SEEN, false),
            tutorialOnFirstRun = boolean(K.TUTORIAL_ON_FIRST_RUN, OnboardingSettings().tutorialOnFirstRun),
        )

    private fun look(look: LookSettings): Map<String, Any?> =
        mapOf(
            K.THEME_MODE to look.themeMode.name,
            K.REDUCE_TRANSPARENCY to look.reduceTransparency,
            K.ACCENT_INTENSITY to look.accentIntensity,
            K.BACKGROUND_DIM to look.backgroundDim,
            K.FONT_COLOR to look.fontColor?.value,
            K.TEXT_SCALE to look.textScale,
            K.BAR_OPACITY to look.barOpacity,
            K.PLAYER_POSITION to look.playerPosition.name,
            K.CORNER_STYLE to look.cornerStyle.name,
            K.COMPACT_PLAYER to look.compactPlayer,
            K.CLEAR_VISUALS_MENU to look.clearVisualsMenu,
            K.RETIRED_ELEMENT to null,
            K.RETIRED_AMBIENT_MODE to null,
        )

    private fun playback(playback: PlaybackSettings): Map<String, Any?> =
        mapOf(
            K.SPEED to playback.speed,
            K.PITCH to playback.pitch.value,
            K.SKIP_SILENCE to playback.skipSilence,
            K.PAUSE_WHEN_UNPLUGGED to playback.pauseWhenUnplugged,
            K.KEEP_SCREEN_ON to playback.keepScreenOn,
            K.AUTO_RESUME to playback.autoResume,
            K.RESUME_LONG_TRACKS to playback.resumeLongTracks,
            K.SLEEP_TIMER to playback.sleepTimer.value,
            K.SLEEP_FINISH_TRACK to playback.sleepFinishTrack,
            K.FADE to playback.fade.value.toInt(),
            K.REPLAY_GAIN to playback.replayGain.ordinal,
            K.REPLAY_GAIN_PREAMP to playback.replayGainPreamp.value,
            K.REPLAY_GAIN_CLIP_GUARD to playback.replayGainClipGuard,
            K.NATIVE_ENGINE to playback.nativeEngine,
            K.GAPLESS to playback.gapless,
            K.CROSSFADE to playback.crossfade.value.toInt(),
            K.CROSSFADE_CURVE to playback.crossfadeCurve.ordinal,
        )

    private fun rest(values: SettingsValues): Map<String, Any?> {
        val equalizer = values.equalizer
        val analysis = values.analysis
        val behavior = values.behavior
        val export = values.export
        val onboarding = values.onboarding
        return mapOf(
            K.EQUALIZER_ENABLED to equalizer.enabled,
            K.EQUALIZER_BANDS to equalizer.bands.joinToString(BAND_SEPARATOR) { it.value.toString() },
            K.EQUALIZER_PRESET to (equalizer.preset?.ordinal ?: NO_PRESET),
            K.BASS_BOOST to equalizer.bassBoost,
            K.LOUDNESS to equalizer.loudness.value,
            K.BEAT_SENSITIVITY to analysis.beatSensitivity,
            K.BEAT_MIN_INTERVAL to analysis.beatMinIntervalMs,
            K.PRESET_MORPH to analysis.presetMorph.value,
            K.REACT_TO_MICROPHONE to analysis.reactToMicrophone,
            K.SYNC_OFFSET to analysis.syncOffset.value.toInt(),
            K.SENSITIVITY to analysis.sensitivity.value,
            K.TOUCH_SMEAR to behavior.touchSmear,
            K.TOUCH_SMEAR_STRENGTH to behavior.touchSmearStrength,
            K.TOUCH_TRANSFORM to behavior.touchTransform,
            K.REDUCED_MOTION to behavior.reducedMotion,
            K.TILT to behavior.tilt.name,
            K.EXPORT_QUALITY to export.quality.name,
            K.EXPORT_FPS to export.frameRate.fps,
            K.EXPORT_RATIO to export.ratio.name,
            K.EXPORT_LOOP_SAFE to export.loopSafe,
            K.EXPORT_CODEC to export.codec.name,
            K.EXPORT_LOUDNESS to LOUDNESS_IDS.getValue(export.loudness),
            K.PRESET_MIRROR to values.folders.presetMirror?.value,
            K.MUSIC_FOLDERS to values.folders.musicFolders.mapTo(mutableSetOf()) { it.value },
            K.PRESET_FOLDER to values.folders.presetFolder?.value,
            K.VIDEO_FOLDER to values.folders.videoFolder?.value,
            K.SAFETY_ACKNOWLEDGED to onboarding.safetyAcknowledged,
            K.SETUP_DONE to onboarding.setupDone,
            K.TUTORIAL_SEEN to onboarding.tutorialSeen,
            K.TUTORIAL_ON_FIRST_RUN to onboarding.tutorialOnFirstRun,
        )
    }

    /** #28's preferences, each in a key of its own that no old file has. */
    private fun preferences(values: SettingsValues): Map<String, Any?> =
        mapOf(
            K.EXPORT_FINISHED_ALERTS to values.export.finishedAlerts,
            K.EXPORT_EDIT_SOURCES to values.export.editSources.name,
            K.BACKGROUND_MUSIC to values.background.music,
            K.BACKGROUND_RENDERS to values.background.renders,
            K.BACKGROUND_RECORDING to values.background.recording,
            K.VISUAL_QUALITY to values.performance.visualQuality.name,
            K.VISUAL_FRAME_RATE to values.performance.visualFrameRate.name,
            K.SAVE_MODE to values.performance.saveMode,
            K.VISUALS_OFF to values.performance.visualsOff,
            K.START_LOOK to startLook(values.behavior.startLook),
            K.TRANSITION_LOOK to values.behavior.transitionLook.id,
        ) + surfaces(values.performance)

    /** #394's per-surface picks: a surface that follows the shared value has its key removed. */
    private fun surfaces(performance: PerformanceSettings): Map<String, Any?> =
        buildMap {
            for (surface in VisualSurface.entries) {
                put(K.surfaceQuality(surface), performance.surfaceQuality[surface]?.name)
                put(K.surfaceFrameRate(surface), performance.surfaceFrameRate[surface]?.name)
            }
        }

    /** Typed reads from a stored map; a value of the wrong type reads as absent. */
    private class Reader(
        private val stored: Map<String, Any?>,
    ) {
        fun boolean(
            key: String,
            default: Boolean,
        ): Boolean = stored[key] as? Boolean ?: default

        fun float(
            key: String,
            default: Float,
        ): Float = stored[key] as? Float ?: default

        fun int(key: String): Int? = stored[key] as? Int

        fun string(key: String): String? = stored[key] as? String

        fun strings(key: String): Set<String> = (stored[key] as? Set<*>).orEmpty().filterIsInstance<String>().toSet()

        inline fun <reified T : Enum<T>> enum(
            key: String,
            default: T,
        ): T = enumOrNull(key) ?: default

        inline fun <reified T : Enum<T>> enumOrNull(key: String): T? = enumValues<T>().firstOrNull { it.name == string(key) }

        /** An enum stored by its position, kept in range as the old code did. */
        fun <T> ordinal(
            key: String,
            entries: List<T>,
            default: T,
        ): T = int(key)?.let { entries[it.coerceIn(entries.indices)] } ?: default
    }
}
