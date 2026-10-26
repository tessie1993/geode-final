package dev.geode.feature.customization.api

/**
 * The groups of the Shared core: the controls every Style has, MilkDrop included (#11). They work on
 * the finished picture. Each group can be kept or left out when a preset is saved ([PresetPart]).
 */
enum class SharedGroup {
    /** The single viewpoint every Style is seen through; on 3D Styles it steers the real flight. */
    CAMERA,
    COLOUR,
    SPEED_AND_ENERGY,

    /** Detail is also the performance control: it sets iteration counts and simulation resolution. */
    DETAIL_AND_GLOW,
    KALEIDOSCOPE_AND_MIRROR,
    TRAILS_AND_ECHO,
    LENS,
    WARP_AND_RIPPLE,
    GLITCH_AND_PIXELATE,
    PARTICLES,
    COLOUR_GRADING,

    /** Layer 2's opacity, influence amount and swap ([Layer]); they do nothing while Layer 2 is off. */
    LAYERS,
}

/** How a property's value is read. Every value is held as a number so any of them can be a target. */
enum class ValueKind {
    /** Any value between the property's minimum and maximum. */
    CONTINUOUS,

    /** A whole number between the minimum and maximum, such as a palette index. */
    STEPPED,

    /** An on/off switch: 0 is off and 1 is on. */
    SWITCH,
}

/**
 * One property of the Shared core, with its range and its starting value.
 *
 * There is deliberately no strobe and no flash property: the No-flash rule has no switch and no strobe
 * effect exists (#11). [luminous] marks properties that move overall brightness; a Reaction on one
 * of them always has a rise time (see the core's Reaction rules), and the engine's limiter bounds them.
 */
enum class SharedProperty(
    val group: SharedGroup,
    val min: Float,
    val max: Float,
    val default: Float,
    val kind: ValueKind = ValueKind.CONTINUOUS,
    val luminous: Boolean = false,
) {
    ZOOM(SharedGroup.CAMERA, 0.3f, 3f, 1f),

    /** Moves the camera forward into a 3D world; the nearest 2D move on flat Styles. */
    PUSH_IN(SharedGroup.CAMERA, 0f, 1f, 0f),
    ROTATION(SharedGroup.CAMERA, -3f, 3f, 0f),

    /** Turns the camera around the subject: left and right. */
    ORBIT_YAW(SharedGroup.CAMERA, -1f, 1f, 0f),

    /** Turns the camera around the subject: up and down. */
    ORBIT_PITCH(SharedGroup.CAMERA, -1f, 1f, 0f),

    /** Adds to a Style's own flight; negative flies backwards. */
    TRAVEL(SharedGroup.CAMERA, -1f, 1f, 0f),
    SWAY(SharedGroup.CAMERA, 0f, 1f, 0f),
    SHAKE(SharedGroup.CAMERA, 0f, 1f, 0f),
    DRIFT_X(SharedGroup.CAMERA, -1f, 1f, 0f),
    DRIFT_Y(SharedGroup.CAMERA, -1f, 1f, 0f),
    ENDLESS_ZOOM(SharedGroup.CAMERA, 0f, 1f, 0f, ValueKind.SWITCH),
    DIVE_SPEED(SharedGroup.CAMERA, 0.05f, 1.2f, 0.3f),

    PALETTE(SharedGroup.COLOUR, 0f, 20f, 0f, ValueKind.STEPPED),
    PALETTE_2(SharedGroup.COLOUR, 0f, 20f, 1f, ValueKind.STEPPED),
    PALETTE_MIX(SharedGroup.COLOUR, 0f, 1f, 0f),
    DUOTONE(SharedGroup.COLOUR, 0f, 1f, 0f, ValueKind.SWITCH),

    /** A perceptually even, cyclic colour map; -1 is off. */
    LUT(SharedGroup.COLOUR, -1f, 4f, -1f, ValueKind.STEPPED),

    /** The shared polar morph. "Morph" means only this; a Style's own shape control is an Extra. */
    MORPH(SharedGroup.COLOUR, 0f, 1f, 0f),
    HUE_SHIFT(SharedGroup.COLOUR, 0f, 1f, 0f),
    HUE_RANGE(SharedGroup.COLOUR, 0f, 1.5f, 1f),
    COLOUR_CYCLE(SharedGroup.COLOUR, 0f, 1f, 0f, ValueKind.SWITCH),
    CYCLE_SPEED(SharedGroup.COLOUR, 0.02f, 0.6f, 0.1f),

    SPEED(SharedGroup.SPEED_AND_ENERGY, 0.05f, 4f, 1f),
    AUDIO_DRIVE(SharedGroup.SPEED_AND_ENERGY, 0.2f, 2.5f, 1f),
    TURBULENCE(SharedGroup.SPEED_AND_ENERGY, 0f, 1.5f, 0f),

    /** Ray-march steps, iteration counts and simulation resolution, within each Style's safe range. */
    DETAIL(SharedGroup.DETAIL_AND_GLOW, 0.25f, 1.5f, 1f),
    GLOW(SharedGroup.DETAIL_AND_GLOW, 0f, 1f, 0f, luminous = true),

    KALEIDOSCOPE(SharedGroup.KALEIDOSCOPE_AND_MIRROR, 0f, 1f, 0f, ValueKind.SWITCH),
    SYMMETRY(SharedGroup.KALEIDOSCOPE_AND_MIRROR, 0f, 16f, 6f, ValueKind.STEPPED),
    MIRROR(SharedGroup.KALEIDOSCOPE_AND_MIRROR, 0f, 1f, 0f, ValueKind.SWITCH),

    /** How much of the last frame echoes into the next. 0 is off, and every Style starts there. */
    TRAILS(SharedGroup.TRAILS_AND_ECHO, 0f, 0.98f, 0f),
    TRAIL_ZOOM(SharedGroup.TRAILS_AND_ECHO, -0.5f, 0.5f, 0f),
    TRAIL_WARP(SharedGroup.TRAILS_AND_ECHO, 0f, 1f, 0f),

    /**
     * A copy of the picture, zoomed by [ECHO_COPY_ZOOM] and flipped by [ECHO_COPY_FLIP], mixed over it for
     * cheap symmetry and depth (#385); 1 is an even blend and 0 is off. The copy keeps the picture's
     * overall brightness, and a change of flip fades the copy out and back in, so it never flashes.
     */
    ECHO_COPY(SharedGroup.TRAILS_AND_ECHO, 0f, 1f, 0f, luminous = true),

    /** How much larger the echo copy is than the picture, about its centre; below 1 it is smaller and tiles. */
    ECHO_COPY_ZOOM(SharedGroup.TRAILS_AND_ECHO, 0.5f, 2f, 1f),

    /** 0 the echo copy is not flipped, 1 flipped left to right (where it starts: a mirror), 2 top to bottom, 3 both. */
    ECHO_COPY_FLIP(SharedGroup.TRAILS_AND_ECHO, 0f, 3f, 1f, ValueKind.STEPPED),

    GRAIN(SharedGroup.LENS, 0f, 1f, 0f),
    VIGNETTE(SharedGroup.LENS, 0f, 1f, 0f),
    CHROMATIC(SharedGroup.LENS, 0f, 1f, 0f),
    SCANLINES(SharedGroup.LENS, 0f, 1f, 0f),
    FISHEYE(SharedGroup.LENS, -1f, 1f, 0f),
    SOFT_FOCUS(SharedGroup.LENS, 0f, 1f, 0f),
    MOTION_BLUR(SharedGroup.LENS, 0f, 1f, 0f),

    WARP(SharedGroup.WARP_AND_RIPPLE, 0f, 1f, 0f),
    RIPPLE(SharedGroup.WARP_AND_RIPPLE, 0f, 1f, 0f),
    TWIST(SharedGroup.WARP_AND_RIPPLE, -1f, 1f, 0f),
    TILE(SharedGroup.WARP_AND_RIPPLE, 1f, 6f, 1f),

    /** Capped at the old safety depth: a Style's own stronger glitch is retired into this one. */
    GLITCH(SharedGroup.GLITCH_AND_PIXELATE, 0f, 0.25f, 0f, luminous = true),
    PIXELATE(SharedGroup.GLITCH_AND_PIXELATE, 0f, 1f, 0f),
    POSTERIZE(SharedGroup.GLITCH_AND_PIXELATE, 0f, 1f, 0f),
    SOLARIZE(SharedGroup.GLITCH_AND_PIXELATE, 0f, 1f, 0f, ValueKind.SWITCH, luminous = true),

    /** The one shared particle layer over every Style; 0 is off. */
    PARTICLES(SharedGroup.PARTICLES, 0f, 1f, 0f),

    /** 0 motes, 1 sparks, 2 dust, 3 bubbles. */
    PARTICLE_KIND(SharedGroup.PARTICLES, 0f, 3f, 0f, ValueKind.STEPPED),
    PARTICLE_SIZE(SharedGroup.PARTICLES, 0.3f, 2.5f, 1f),

    SATURATION(SharedGroup.COLOUR_GRADING, 0f, 1.5f, 1f),
    BRIGHTNESS(SharedGroup.COLOUR_GRADING, 0.2f, 1.25f, 1f, luminous = true),
    INTENSITY(SharedGroup.COLOUR_GRADING, 0.2f, 1.25f, 1f, luminous = true),
    CONTRAST(SharedGroup.COLOUR_GRADING, 0.3f, 1.25f, 1f, luminous = true),
    GAMMA(SharedGroup.COLOUR_GRADING, 0.3f, 2.5f, 1f, luminous = true),
    TEMPERATURE(SharedGroup.COLOUR_GRADING, -1f, 1f, 0f),
    INVERT(SharedGroup.COLOUR_GRADING, 0f, 1f, 0f, ValueKind.SWITCH, luminous = true),

    /**
     * Red/blue anaglyph 3D for red/cyan glasses (#388), with depth read from the picture's brightness.
     * Each eye keeps the picture's brightness and the switch fades in and out, so it never flashes.
     */
    ANAGLYPH(SharedGroup.LENS, 0f, 1f, 0f, ValueKind.SWITCH, luminous = true),

    /** How strongly Layer 2 is laid over Layer 1; held lower by the flashing blend modes ([LayerBlend.maxOpacity]). */
    LAYER_OPACITY(SharedGroup.LAYERS, 0f, 1f, 0.5f, luminous = true),

    /** How strongly Layer 2 acts on Layer 1 ([LayerInfluence]). */
    LAYER_INFLUENCE(SharedGroup.LAYERS, 0f, 1f, 0.5f, luminous = true),

    /** 0 lays Layer 2 over Layer 1, 1 lays Layer 1 over Layer 2; between, a crossfade of the two. */
    LAYER_SWAP(SharedGroup.LAYERS, 0f, 1f, 0f, luminous = true),
}
