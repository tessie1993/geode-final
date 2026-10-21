package dev.geode.feature.customization.api

import dev.geode.engine.types.SceneId

/**
 * The preset format this version writes. Version 1 was the flat 135-field `SceneParams` file, which
 * is converted once on upgrade.
 */
const val PRESET_VERSION: Int = 2

/**
 * The parts of a Customization a person can tick when saving a preset (#11). The Shared core parts
 * ([group] set) and [SOUND] are shared: they apply on any Style. The rest belong to the Style the
 * preset was made on, and apply only there.
 */
enum class PresetPart(
    val group: SharedGroup?,
) {
    /** The Style itself: applying the preset switches to it. */
    STYLE(null),
    CAMERA(SharedGroup.CAMERA),
    COLOUR(SharedGroup.COLOUR),
    SPEED_AND_ENERGY(SharedGroup.SPEED_AND_ENERGY),
    DETAIL_AND_GLOW(SharedGroup.DETAIL_AND_GLOW),
    KALEIDOSCOPE_AND_MIRROR(SharedGroup.KALEIDOSCOPE_AND_MIRROR),
    TRAILS_AND_ECHO(SharedGroup.TRAILS_AND_ECHO),
    LENS(SharedGroup.LENS),
    WARP_AND_RIPPLE(SharedGroup.WARP_AND_RIPPLE),
    GLITCH_AND_PIXELATE(SharedGroup.GLITCH_AND_PIXELATE),
    PARTICLES(SharedGroup.PARTICLES),
    COLOUR_GRADING(SharedGroup.COLOUR_GRADING),

    /** The normaliser's trims of the Sound signals. */
    SOUND(null),
    EXTRAS(null),
    MACROS(null),
    REACTIONS(null),
    MODULATORS(null),
}

/**
 * A saved preset: only the [parts] the person ticked are filled in, the rest are empty. [version]
 * is the format it was written in; [style] is the Style it was made on.
 */
data class Preset(
    val version: Int,
    val name: String,
    val style: SceneId,
    val parts: Set<PresetPart>,
    val shared: Map<SharedProperty, Float> = emptyMap(),
    val extras: Map<ExtraKind, Float> = emptyMap(),
    val macros: List<Macro> = emptyList(),
    val reactions: List<Reaction> = emptyList(),
    val modulators: List<Modulator> = emptyList(),
    val signals: Map<SoundSignal, SignalTrim> = emptyMap(),
)
