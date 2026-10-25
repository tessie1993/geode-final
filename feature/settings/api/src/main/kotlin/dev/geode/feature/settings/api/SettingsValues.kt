package dev.geode.feature.settings.api

import dev.geode.engine.types.Millis

/**
 * Every setting the app has, grouped as today's Settings tabs group them (#38). Immutable: a change
 * is a new value. Each section names its defaults and the range each value is kept in.
 */
data class SettingsValues(
    val look: LookSettings = LookSettings(),
    val playback: PlaybackSettings = PlaybackSettings(),
    val equalizer: EqualizerSettings = EqualizerSettings(),
    val analysis: AnalysisSettings = AnalysisSettings(),
    val behavior: BehaviorSettings = BehaviorSettings(),
    val export: ExportSettings = ExportSettings(),
    val folders: FolderSettings = FolderSettings(),
    val onboarding: OnboardingSettings = OnboardingSettings(),
    val background: BackgroundSettings = BackgroundSettings(),
    val performance: PerformanceSettings = PerformanceSettings(),
)

/** A colour as 0xAARRGGBB. */
@JvmInline
value class Argb(
    val value: Int,
)

/** A folder the person picked, as its persisted document-tree uri. */
@JvmInline
value class FolderUri(
    val value: String,
)

/** A level in millibels (hundredths of a decibel), as the equalizer takes it. */
@JvmInline
value class Millibels(
    val value: Int,
) : Comparable<Millibels> {
    override fun compareTo(other: Millibels): Int = value.compareTo(other.value)
}

/** A gain in decibels. */
@JvmInline
value class Decibels(
    val value: Float,
) : Comparable<Decibels> {
    override fun compareTo(other: Decibels): Int = value.compareTo(other.value)
}

/** A pitch shift in semitones. */
@JvmInline
value class Semitones(
    val value: Float,
) : Comparable<Semitones> {
    override fun compareTo(other: Semitones): Int = value.compareTo(other.value)
}

/** A length of time in seconds, where a fraction of a second matters. */
@JvmInline
value class Seconds(
    val value: Float,
) : Comparable<Seconds> {
    override fun compareTo(other: Seconds): Int = value.compareTo(other.value)
}

/** A length of time in whole minutes. */
@JvmInline
value class Minutes(
    val value: Int,
) : Comparable<Minutes> {
    override fun compareTo(other: Minutes): Int = value.compareTo(other.value)
}

enum class PlayerPosition {
    TOP,
    BOTTOM,
}

enum class CornerStyle {
    SHARP,
    ROUNDED,
    PILL,
}

/**
 * Ambient: how much the Element's Hero scene and its floating assets move by themselves, never with
 * the music. They show on Home only, whatever the mode. Drifting holds still under battery saver,
 * reduced motion or CPU/GPU save mode; Off stays off.
 */
enum class AmbientMode {
    DRIFTING,
    STILL,
    OFF,
}

/**
 * One selectable skin for the App UI (see CONTEXT.md): its material, palette, type, shapes and
 * motion. It never changes navigation, layout or what a control does. The first wave is these four,
 * dark only; the look of each belongs to :design.
 */
enum class Element {
    WATER,
    WOOD,
    STONE,
    BIOLUMINESCENCE,
    ;

    companion object {
        /** The Element a fresh install starts on, and what an unknown stored value falls back to. */
        val Default = WATER
    }
}

/** The Theme section (was Look): the Element, accent, text and layout of the App UI. */
data class LookSettings(
    val element: Element = Element.Default,
    val accentIntensity: Float = ACCENT_INTENSITY_DEFAULT,
    val backgroundDim: Float = BACKGROUND_DIM_DEFAULT,
    /** The text colour chosen over the theme's own, or null for the theme's. */
    val fontColor: Argb? = null,
    val textScale: Float = TEXT_SCALE_DEFAULT,
    val barOpacity: Float = BAR_OPACITY_DEFAULT,
    val playerPosition: PlayerPosition = PlayerPosition.BOTTOM,
    val cornerStyle: CornerStyle = CornerStyle.ROUNDED,
    val compactPlayer: Boolean = false,
    val clearVisualsMenu: Boolean = false,
    val ambientMode: AmbientMode = AmbientMode.DRIFTING,
) {
    companion object {
        const val ACCENT_INTENSITY_DEFAULT = 1f
        val ACCENT_INTENSITY: ClosedFloatingPointRange<Float> = 0.5f..1.5f
        const val BACKGROUND_DIM_DEFAULT = 0f
        val BACKGROUND_DIM: ClosedFloatingPointRange<Float> = 0f..0.6f
        const val TEXT_SCALE_DEFAULT = 1f
        val TEXT_SCALE: ClosedFloatingPointRange<Float> = 0.85f..1.3f
        const val BAR_OPACITY_DEFAULT = 0.72f
        val BAR_OPACITY: ClosedFloatingPointRange<Float> = 0.2f..1f
    }
}

enum class ReplayGainMode {
    OFF,
    TRACK,
    ALBUM,
}

enum class CrossfadeCurve {
    LINEAR,
    EQUAL_POWER,
    SMOOTH,
}

/** The playback part of the Audio tab. */
data class PlaybackSettings(
    val speed: Float = SPEED_DEFAULT,
    val pitch: Semitones = Semitones(0f),
    val skipSilence: Boolean = false,
    val pauseWhenUnplugged: Boolean = true,
    val keepScreenOn: Boolean = false,
    val autoResume: Boolean = false,
    val resumeLongTracks: Boolean = true,
    /** The length the sleep timer was last started with; zero when it never was. */
    val sleepTimer: Minutes = Minutes(0),
    val sleepFinishTrack: Boolean = false,
    val fade: Millis = Millis.ZERO,
    val replayGain: ReplayGainMode = ReplayGainMode.OFF,
    val replayGainPreamp: Decibels = Decibels(0f),
    val replayGainClipGuard: Boolean = true,
    val nativeEngine: Boolean = false,
    val gapless: Boolean = true,
    val crossfade: Millis = Millis.ZERO,
    val crossfadeCurve: CrossfadeCurve = CrossfadeCurve.EQUAL_POWER,
) {
    companion object {
        const val SPEED_DEFAULT = 1f
        val SPEED: ClosedFloatingPointRange<Float> = 0.5f..2f
        val PITCH: ClosedRange<Semitones> = Semitones(-6f)..Semitones(6f)
        val SLEEP_TIMER_CHOICES: List<Minutes> = listOf(Minutes(15), Minutes(30), Minutes(45), Minutes(60))
        val FADE: ClosedRange<Millis> = Millis.ZERO..Millis(6_000)
        val REPLAY_GAIN_PREAMP: ClosedRange<Decibels> = Decibels(-15f)..Decibels(15f)
        val CROSSFADE: ClosedRange<Millis> = Millis.ZERO..Millis(12_000)
    }
}

/** The equalizer's built-in presets, low band first, in millibels. Their names belong to the screens. */
enum class EqualizerPreset(
    val levels: List<Millibels>,
) {
    FLAT(millibels(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
    BASS(millibels(600, 500, 350, 150, 0, 0, 0, 0, 0, 0)),
    TREBLE(millibels(0, 0, 0, 0, 0, 100, 250, 400, 500, 550)),
    VOCAL(millibels(-200, -150, -50, 100, 300, 350, 300, 150, 0, -100)),
    ROCK(millibels(450, 350, 150, -50, -150, -50, 150, 300, 400, 450)),
    ELECTRONIC(millibels(500, 400, 100, 0, -150, 0, 150, 300, 450, 500)),
}

private fun millibels(vararg levels: Int): List<Millibels> = levels.map(::Millibels)

/** The equalizer part of the Audio tab. */
data class EqualizerSettings(
    val enabled: Boolean = false,
    /** One level per band, [BANDS] of them, low band first. */
    val bands: List<Millibels> = List(BANDS) { Millibels(0) },
    /** The preset the bands were last set from, or null once a band was moved by hand. */
    val preset: EqualizerPreset? = null,
    /** Bass boost strength, in thousandths. */
    val bassBoost: Int = 0,
    val loudness: Millibels = Millibels(0),
) {
    companion object {
        const val BANDS = 10
        val BAND_LEVEL: ClosedRange<Millibels> = Millibels(-1_500)..Millibels(1_500)
        val BASS_BOOST: IntRange = 0..1_000
        val LOUDNESS: ClosedRange<Millibels> = Millibels(0)..Millibels(1_000)
    }
}

/** Beat-detection tunings offered as one tap. */
enum class BeatTuning(
    val sensitivity: Float,
    val minIntervalMs: Float,
) {
    DEFAULT(sensitivity = 3f, minIntervalMs = 1_000f / 3f),
    SLOW_TRACK(sensitivity = 5.5f, minIntervalMs = 700f),
}

/** The analysis and live-input parts of the Audio tab. */
data class AnalysisSettings(
    val beatSensitivity: Float = BeatTuning.DEFAULT.sensitivity,
    /** The shortest gap between two beats, in milliseconds; fractional, as it was always stored. */
    val beatMinIntervalMs: Float = BeatTuning.DEFAULT.minIntervalMs,
    /** How long a preset load glides in. */
    val presetMorph: Seconds = PRESET_MORPH_DEFAULT,
    /** Whether the Visual reacts to the microphone when the app starts. Capture reads it. */
    val reactToMicrophone: Boolean = false,
) {
    companion object {
        val BEAT_SENSITIVITY: ClosedFloatingPointRange<Float> = 1.5f..8f
        val BEAT_MIN_INTERVAL_MS: ClosedFloatingPointRange<Float> = 200f..1_200f
        val PRESET_MORPH_DEFAULT = Seconds(2f)
        val PRESET_MORPH: ClosedRange<Seconds> = Seconds(0f)..Seconds(8f)
    }
}

/** Application preferences (was Behavior): touch and motion. */
data class BehaviorSettings(
    val touchSmear: Boolean = false,
    val touchSmearStrength: Float = TOUCH_SMEAR_STRENGTH_DEFAULT,
    val touchTransform: Boolean = true,
    val reducedMotion: Boolean = false,
) {
    companion object {
        const val TOUCH_SMEAR_STRENGTH_DEFAULT = 1f
        val TOUCH_SMEAR_STRENGTH: ClosedFloatingPointRange<Float> = 0.2f..2f
    }
}

/** The export sizes (#22), smallest first. Stored by name, so adding a size never moves another. */
enum class ExportQuality {
    SD480,
    HD720,
    FHD1080,
    QHD1440,
    UHD4K,
}

enum class ExportFrameRate(
    val fps: Int,
) {
    FPS30(30),
    FPS60(60),
}

enum class ExportRatio {
    R16_9,
    R9_16,
    R1_1,
    R4_5,
    R4_3,
    R21_9,
}

enum class ExportCodec {
    H264,
    HEVC,
}

/** The loudness a render is levelled to. */
enum class ExportLoudness {
    YOUTUBE,
    SHORTS_AND_TIKTOK,
    LEAVE_AS_IS,
}

/** Export preferences (was Export): what new renders start with. */
data class ExportSettings(
    val quality: ExportQuality = ExportQuality.FHD1080,
    val frameRate: ExportFrameRate = ExportFrameRate.FPS60,
    val ratio: ExportRatio = ExportRatio.R16_9,
    val loopSafe: Boolean = false,
    val codec: ExportCodec = ExportCodec.H264,
    val loudness: ExportLoudness = ExportLoudness.LEAVE_AS_IS,
    /** A notification when a render finishes, with Share and Open buttons (#28). */
    val finishedAlerts: Boolean = true,
)

/**
 * The Default paths section (#101): the folder Studio saves its videos to, the music folders the
 * library scans, the preset folder, and the folder saved presets are mirrored to.
 */
data class FolderSettings(
    /**
     * Where Studio saves Renders, Recordings, Edits and Shorts (#21): one folder for every Project,
     * picked with the system folder picker in Studio's first-run step. Null until it is picked.
     */
    val videoFolder: FolderUri? = null,
    /** A folder every saved preset is also copied to, or null for none. */
    val presetMirror: FolderUri? = null,
    val musicFolders: Set<FolderUri> = emptySet(),
    /** Where saved presets and MilkDrop presets live, or null for the app's own storage. */
    val presetFolder: FolderUri? = null,
)

/** What the person has already been through, so the app does not ask again. */
data class OnboardingSettings(
    val safetyAcknowledged: Boolean = false,
    val setupDone: Boolean = false,
    val tutorialSeen: Boolean = false,
    /** The walkthrough's "show this next time" switch, in the Help tab. */
    val tutorialOnFirstRun: Boolean = true,
)

/**
 * Run in background (#28), in Application preferences: what keeps going when the person leaves the
 * app or locks the phone. Each is its own switch. Playback, Studio and the Recording read them.
 */
data class BackgroundSettings(
    /** Music keeps playing, with controls in the notification; off pauses it on leaving the app. */
    val music: Boolean = true,
    /** Renders keep going while other apps are used, with a progress notification. */
    val renders: Boolean = true,
    /** A live Recording of the Visual keeps capturing on switching away. */
    val recording: Boolean = false,
)

/**
 * How sharp the Visual is drawn on screen, as a ceiling on its render scale. The automatic cooling
 * when the phone is hot can only lower it further. Renders always draw at their exact export size.
 */
enum class VisualQuality(
    val renderScale: Float,
) {
    HIGH(1f),
    BALANCED(0.85f),
    SAVER(0.7f),
}

/** How often the Visual is drawn on screen: [fps] frames a second, or null for the screen's maximum. */
enum class VisualFrameRate(
    val fps: Int?,
) {
    FPS30(30),
    FPS60(60),
    SCREEN_MAX(null),
}

/**
 * The CPU/GPU section (#101) and the CPU/GPU save mode switch, which sits in Application preferences.
 * Cooling down when the phone is hot is automatic and has no setting.
 *
 * Readers take the effective values ([visual], [frameRate], [floatingAssetsHeldStill],
 * [rendersSlowed]), which already apply [saveMode]; the chosen ones stay as they were, so turning save
 * mode off brings them back.
 */
data class PerformanceSettings(
    val visualQuality: VisualQuality = VisualQuality.HIGH,
    val visualFrameRate: VisualFrameRate = VisualFrameRate.SCREEN_MAX,
    /** Saves battery and heat: lower Visual quality, 30 fps, still floating assets, slower renders. */
    val saveMode: Boolean = false,
    /**
     * Visuals off (#196): nothing draws or analyses for the Visual, and no Render or Recording can be
     * made. The Element and its Hero scene still run. Visuals and Studio read it here.
     */
    val visualsOff: Boolean = false,
) {
    /** The Visual quality to draw at: [VisualQuality.SAVER] in save mode, else the chosen one. */
    val visual: VisualQuality get() = if (saveMode) VisualQuality.SAVER else visualQuality

    /** The frame rate to draw at: 30 in save mode, else the chosen one. */
    val frameRate: VisualFrameRate get() = if (saveMode) VisualFrameRate.FPS30 else visualFrameRate

    /** The floating assets hold still (Drifting acts as Still), as with battery saver (#100). */
    val floatingAssetsHeldStill: Boolean get() = saveMode

    /** Renders run slower, on fewer threads, so the phone stays cool. */
    val rendersSlowed: Boolean get() = saveMode
}
