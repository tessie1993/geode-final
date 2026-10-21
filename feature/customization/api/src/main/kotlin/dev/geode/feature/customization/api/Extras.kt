package dev.geode.feature.customization.api

/**
 * The generic Extras: a Style's own controls on top of the Shared core, with the same names on every
 * Style (#11, owner decision round 1). Each Style maps the ones it has onto its own variables through
 * its manifest ([ExtraSpec]). A Customization holds every Extra as 0 to 1; the engine turns that into
 * the Style's own native range.
 *
 * Morph's old second meaning is gone: hexgrid's cell scale is [SCALE], spiral's arms and solar's rays
 * are [COUNT], and morphogen's hold time is [HOLD].
 */
enum class ExtraKind {
    /** Pattern or cell scale, ring frequency. */
    SCALE,

    /** How many: bars, stars, arms, rays, balls, lines, folds, standing waves. */
    COUNT,

    /** The size of each element: radius, blob, splat. */
    SIZE,

    /** Line, rod or curtain thickness. */
    THICKNESS,

    /** The speed of the Style's own flight (tunnel travel, star warp, feedback dive). */
    FLIGHT,

    /** The Style's own rotation rate. */
    SPIN,

    /** Orbit radius or swing of moving parts. */
    ORBIT,

    /** Curl, vortex or swirl strength. */
    SWIRL,

    /** The Style's own internal motion rate: flow, scroll, drift. */
    DRIFT,

    /** Structural depth: fold depth, octaves, recursion. Not performance; that is Detail. */
    COMPLEXITY,

    /** Edge or rim sharpness. */
    EDGE,

    /** Fog or haze density. */
    FOG,

    /** How long marks last: trail decay, pheromone decay, dye dissipation, feedback. */
    DECAY,

    /** How thick the medium is: velocity dissipation, damping. */
    VISCOSITY,

    /** Growth rate: Lenia's centre, Gray-Scott's feed. */
    GROWTH,

    /** Growth width or kill rate: Lenia's sigma, Gray-Scott's kill. */
    SPREAD,

    /** Push strength: splat force, deposit, ripple strength. */
    FORCE,

    /** How far agents sense. */
    REACH,

    /** How sharply agents turn. */
    TURN,

    /** How long a form holds before it changes. */
    HOLD,

    /** Which built-in variant: a rule, mode, field or geometry. Stepped. */
    VARIANT,

    /** The point of interest, across. */
    CENTER_X,

    /** The point of interest, up and down. */
    CENTER_Y,
}

/**
 * One Extra a Style declares: [kind], the Style's own native range [nativeMin]..[nativeMax] that 0..1
 * maps onto, and its starting position [default] (0..1), chosen so that an unedited Style looks as it
 * did before the Customization model. [stepped] Extras snap to whole native values.
 */
data class ExtraSpec(
    val kind: ExtraKind,
    val nativeMin: Float,
    val nativeMax: Float,
    val default: Float,
    val stepped: Boolean = false,
)
