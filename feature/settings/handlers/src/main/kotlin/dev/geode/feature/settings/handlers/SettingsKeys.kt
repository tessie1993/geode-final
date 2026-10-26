package dev.geode.feature.settings.handlers

/**
 * Where each setting is stored. The keys and their formats are today's, unchanged (#38): a key in
 * DataStore has the name and type it had in its SharedPreferences file. The files they come from
 * are listed in [LEGACY_FILES], with only the keys that are settings, so nothing else in those
 * files (bookmarks, the queue's shuffle and repeat, Customization) is touched. Settings retired
 * before this feature (#9: themes, following the system's dark mode, the start-up animation, key
 * colours, a second screen, bit-perfect output) are swept by the old code and are not read here.
 */
internal object SettingsKeys {
    /** The DataStore file every setting now lives in. */
    const val FILE = "geode-settings"

    /** The format of what is stored, migrated step by step (ADR-0004). Absent until migrated. */
    const val VERSION = "settings_version"
    const val CURRENT_VERSION = 1

    // Look (geode-prefs).

    /**
     * New with the Elements (#103), so in no old file. Stored by name; the forest theme ids an earlier
     * design used read as the matching Element ([SettingsCodec]).
     */
    const val ELEMENT = "gui_element"
    const val ACCENT_INTENSITY = "gui_accent_intensity"
    const val BACKGROUND_DIM = "gui_background_dim"
    const val FONT_COLOR = "gui_font_color"
    const val TEXT_SCALE = "gui_text_scale"
    const val BAR_OPACITY = "gui_opacity"
    const val PLAYER_POSITION = "gui_player_pos"
    const val CORNER_STYLE = "gui_corner"
    const val COMPACT_PLAYER = "gui_compact_player"
    const val CLEAR_VISUALS_MENU = "gui_clear_visuals_menu"

    /** Ambient (#100), new so in no old file. Stored by name. */
    const val AMBIENT_MODE = "gui_ambient_mode"

    // Playback (geode-player).
    const val SPEED = "speed"
    const val PITCH = "pitch_semitones"
    const val SKIP_SILENCE = "skip_silence"
    const val PAUSE_WHEN_UNPLUGGED = "pause_on_noisy"
    const val KEEP_SCREEN_ON = "keep_screen_on"
    const val AUTO_RESUME = "auto_resume"
    const val RESUME_LONG_TRACKS = "resume_long_tracks"
    const val SLEEP_TIMER = "sleep_timer_minutes"
    const val SLEEP_FINISH_TRACK = "sleep_finish_track"
    const val FADE = "fade_ms"
    const val REPLAY_GAIN = "replaygain_mode"
    const val REPLAY_GAIN_PREAMP = "replaygain_preamp_db"
    const val REPLAY_GAIN_CLIP_GUARD = "replaygain_clip_guard"
    const val NATIVE_ENGINE = "native_engine"
    const val GAPLESS = "gapless"
    const val CROSSFADE = "crossfade_ms"
    const val CROSSFADE_CURVE = "crossfade_curve"

    // Equalizer (geode-audiofx).
    const val EQUALIZER_ENABLED = "enabled"
    const val EQUALIZER_BANDS = "band_levels"
    const val EQUALIZER_PRESET = "preset"
    const val BASS_BOOST = "bass"
    const val LOUDNESS = "loudness"

    // Analysis and live input (geode-prefs).
    const val BEAT_SENSITIVITY = "beat_sigma"
    const val BEAT_MIN_INTERVAL = "beat_min_interval_ms"
    const val PRESET_MORPH = "gui_preset_morph_seconds"
    const val REACT_TO_MICROPHONE = "gui_mic_reactive"

    // Behavior (geode-prefs).
    const val TOUCH_SMEAR = "gui_touch_smear"
    const val TOUCH_SMEAR_STRENGTH = "gui_touch_smear_strength"
    const val TOUCH_TRANSFORM = "gui_touch_transform"
    const val REDUCED_MOTION = "gui_reduced_motion"
    const val TILT = "gui_tilt"

    // Export (geode-prefs).
    const val EXPORT_QUALITY = "export_quality"
    const val EXPORT_FPS = "export_fps"
    const val EXPORT_RATIO = "export_ratio"
    const val EXPORT_LOOP_SAFE = "export_loop"
    const val EXPORT_CODEC = "export_codec"
    const val EXPORT_LOUDNESS = "export_loudness_target"

    // Default paths (geode-prefs, geode-library).
    const val PRESET_MIRROR = "preset_mirror_uri"
    const val MUSIC_FOLDERS = "roots"

    /** New with the Default paths section (#101), so in no old file. */
    const val PRESET_FOLDER = "preset_folder_uri"

    /** New with Studio (#21), so in no old file: the old Studio always saved to Movies/Geode. */
    const val VIDEO_FOLDER = "video_folder_uri"

    // New with #28's preferences, so in no old file.
    const val EXPORT_FINISHED_ALERTS = "export_finished_alerts"
    const val BACKGROUND_MUSIC = "background_music"
    const val BACKGROUND_RENDERS = "background_renders"
    const val BACKGROUND_RECORDING = "background_recording"
    const val VISUAL_QUALITY = "visual_quality"
    const val VISUAL_FRAME_RATE = "visual_frame_rate"
    const val SAVE_MODE = "cpu_gpu_save_mode"

    // New with #196, so in no old file.
    const val VISUALS_OFF = "visuals_off"

    /**
     * New with #383, so in no old file: the look Geode starts on, as `style:<id>` or `preset:<id>`;
     * absent for the last look.
     */
    const val START_LOOK = "start_look"

    // Onboarding (geode-prefs).
    const val SAFETY_ACKNOWLEDGED = "gui_safety_acknowledged"
    const val SETUP_DONE = "gui_setup_done"
    const val TUTORIAL_SEEN = "gui_tutorial_seen"
    const val TUTORIAL_ON_FIRST_RUN = "gui_tutorial_on_first_run"

    // Read only, from older builds: each is turned into a current setting and never written.

    /** Before the font colour choice: white text or not. */
    const val LEGACY_WHITE_FONT = "gui_white_font"

    /** Before the morph length was in seconds: a count of beats at [LEGACY_MORPH_BPM]. */
    const val LEGACY_MORPH_BEATS = "morph_beats"

    /** The old three-way safety answer; any answer means the notice was seen. */
    const val LEGACY_SAFETY_CHOICE = "gui_safety_choice"

    /** The removed "what do you use Geode for?" answer. Its value is ignored; having one means setup was done. */
    const val LEGACY_INTENT = "gui_intent"

    /** The old preferences files, and the keys in each that are settings. */
    val LEGACY_FILES: Map<String, Set<String>> =
        mapOf(
            "geode-prefs" to
                setOf(
                    ACCENT_INTENSITY,
                    BACKGROUND_DIM,
                    FONT_COLOR,
                    TEXT_SCALE,
                    BAR_OPACITY,
                    PLAYER_POSITION,
                    CORNER_STYLE,
                    COMPACT_PLAYER,
                    CLEAR_VISUALS_MENU,
                    BEAT_SENSITIVITY,
                    BEAT_MIN_INTERVAL,
                    PRESET_MORPH,
                    REACT_TO_MICROPHONE,
                    TOUCH_SMEAR,
                    TOUCH_SMEAR_STRENGTH,
                    TOUCH_TRANSFORM,
                    REDUCED_MOTION,
                    EXPORT_QUALITY,
                    EXPORT_FPS,
                    EXPORT_RATIO,
                    EXPORT_LOOP_SAFE,
                    EXPORT_CODEC,
                    EXPORT_LOUDNESS,
                    PRESET_MIRROR,
                    SAFETY_ACKNOWLEDGED,
                    SETUP_DONE,
                    TUTORIAL_SEEN,
                    TUTORIAL_ON_FIRST_RUN,
                    LEGACY_WHITE_FONT,
                    LEGACY_MORPH_BEATS,
                    LEGACY_SAFETY_CHOICE,
                    LEGACY_INTENT,
                ),
            "geode-player" to
                setOf(
                    SPEED,
                    PITCH,
                    SKIP_SILENCE,
                    PAUSE_WHEN_UNPLUGGED,
                    KEEP_SCREEN_ON,
                    AUTO_RESUME,
                    RESUME_LONG_TRACKS,
                    SLEEP_TIMER,
                    SLEEP_FINISH_TRACK,
                    FADE,
                    REPLAY_GAIN,
                    REPLAY_GAIN_PREAMP,
                    REPLAY_GAIN_CLIP_GUARD,
                    NATIVE_ENGINE,
                    GAPLESS,
                    CROSSFADE,
                    CROSSFADE_CURVE,
                ),
            "geode-audiofx" to
                setOf(
                    EQUALIZER_ENABLED,
                    EQUALIZER_BANDS,
                    EQUALIZER_PRESET,
                    BASS_BOOST,
                    LOUDNESS,
                ),
            "geode-library" to setOf(MUSIC_FOLDERS),
        )

    /** The tempo the old beat-count morph assumed. */
    const val LEGACY_MORPH_BPM = 120f
    const val SECONDS_PER_MINUTE = 60f
}
