package dev.geode.feature.customization.api

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId

/**
 * How Layer 2 is laid over Layer 1, with Photoshop's names and the W3C Compositing and Blending formulas
 * (the engine's Layers.hpp, in the same order; presets save the names). With the layers swapped
 * ([SharedProperty.LAYER_SWAP] at 1) Overlay gives Hard Light and Luminosity gives Color. [maxOpacity] is
 * the No-flash rule on blending: the modes that invert where the two pictures move against each other
 * are held to a quarter; every other mode reaches full opacity.
 */
enum class LayerBlend(
    val maxOpacity: Float = 1f,
) {
    NORMAL,

    /** Light adds to light in linear light, rolled off before white. */
    ADD,
    SCREEN,

    /** Whichever layer is brighter at each point: never brighter than the brighter of the two. */
    LIGHTEN,
    MULTIPLY,
    OVERLAY,
    SOFT_LIGHT,
    DIFFERENCE(QUARTER),
    EXCLUSION(QUARTER),

    /** Layer 1's colour at Layer 2's brightness. */
    LUMINOSITY,
}

/** How far the flashing blend modes may go: the flash depth, as the engine's VisualSafety holds it. */
private const val QUARTER = 0.25f

/** How Layer 2 acts on Layer 1 before it is laid over it, by [SharedProperty.LAYER_INFLUENCE]. */
enum class LayerInfluence {
    NONE,

    /** Layer 2's bright edges bend Layer 1, like looking through rippled glass. */
    BEND,

    /** Layer 1 flows along Layer 2's contours. */
    SWIRL,

    /** Layer 1 shows only where Layer 2 is lit: a luma key. */
    CUT_OUT,

    /** Layer 1 keeps its own brightness in Layer 2's colours. */
    TINT,

    /** Layer 1 shows only along Layer 2's edges. */
    OUTLINE,
}

/** The shape a [LayerLoop] moves its targets in. */
enum class LoopShape {
    SINE,
    TRIANGLE,

    /** A triangle eased at both ends: it slows into each turn. */
    PING_PONG,

    /** A new level each step, on the beat once the live analysis hears one, glided into, never cut. */
    BEAT_STEPS,
}

/**
 * How long one round of a [LayerLoop] takes (one step, for [LoopShape.BEAT_STEPS]): [beats] beats of
 * the tempo the live analysis hears, or [seconds]. Nothing faster is offered, so no loop comes near a
 * flash; the engine slews Layer 2 besides.
 */
enum class LoopRate(
    val beats: Int = 0,
    val seconds: Int = 0,
) {
    TWO_BEATS(beats = 2),
    ONE_BAR(beats = 4),
    FOUR_SECONDS(seconds = 4),
    EIGHT_SECONDS(seconds = 8),
    SIXTEEN_SECONDS(seconds = 16),
    ;

    val beatSynced: Boolean
        get() = beats > 0

    /** The round's length in time when it is not beat-synced; a beat-synced one counts beats instead. */
    val length: Millis
        get() = Millis(seconds * MILLIS_PER_SECOND)
}

private const val MILLIS_PER_SECOND = 1000L

/**
 * What a [LayerLoop] moves. Opacity and influence dip from the person's own value by the loop's depth;
 * the swap rises from it, so Layer 1 comes up over Layer 2 and back.
 */
enum class LoopTarget(
    val property: SharedProperty,
    val dips: Boolean,
) {
    OPACITY(SharedProperty.LAYER_OPACITY, dips = true),
    INFLUENCE(SharedProperty.LAYER_INFLUENCE, dips = true),
    SWAP(SharedProperty.LAYER_SWAP, dips = false),
}

/**
 * Layer 2's loop: its [targets] moved in [shape] at [rate], by [depth] (0..1 of their range). The engine
 * runs it as one Modulator and a Reaction per target, the same mod matrix every other Modulator runs in,
 * so it is frame-rate independent and kept to the No-flash rule.
 */
data class LayerLoop(
    val shape: LoopShape = LoopShape.SINE,
    val rate: LoopRate = LoopRate.ONE_BAR,
    val depth: Float = DEFAULT_DEPTH,
    val targets: Set<LoopTarget> = setOf(LoopTarget.OPACITY),
) {
    companion object {
        const val DEFAULT_DEPTH: Float = 0.5f
    }
}

/** The built-in loops, ready to pick: each is an ordinary [LayerLoop] the person can then change. */
enum class LoopPreset(
    val loop: LayerLoop,
) {
    /** Layer 2 breathes in and out over every bar. */
    BREATHE(LayerLoop(LoopShape.SINE, LoopRate.ONE_BAR, 0.6f, setOf(LoopTarget.OPACITY))),

    /** The two layers slowly trade places on top. */
    TRADE_PLACES(LayerLoop(LoopShape.PING_PONG, LoopRate.SIXTEEN_SECONDS, 1f, setOf(LoopTarget.SWAP))),

    /** Opacity and influence step with the beat. */
    PULSE(LayerLoop(LoopShape.BEAT_STEPS, LoopRate.TWO_BEATS, 0.5f, setOf(LoopTarget.OPACITY, LoopTarget.INFLUENCE))),

    /** The influence drifts up and down. */
    DRIFT(LayerLoop(LoopShape.TRIANGLE, LoopRate.EIGHT_SECONDS, 0.8f, setOf(LoopTarget.INFLUENCE))),
}

/**
 * Layer 2 (CONTEXT.md, Layer): a second Style, [style], drawn over the Style itself (Layer 1) and
 * composited with it by [blend], acting on it by [influence], and moving by itself with [loop]. Its
 * opacity, influence amount and swap are the Shared core's [SharedProperty.LAYER_OPACITY],
 * [SharedProperty.LAYER_INFLUENCE] and [SharedProperty.LAYER_SWAP], so any Reaction, Modulator, Macro or
 * lane can move them too. No [style] is Layer 2 off. MilkDrop cannot be Layer 2: its one engine is the
 * MilkDrop preset the person picked.
 */
data class Layer(
    val style: SceneId? = null,
    val blend: LayerBlend = LayerBlend.SCREEN,
    val influence: LayerInfluence = LayerInfluence.NONE,
    val loop: LayerLoop? = null,
) {
    val on: Boolean
        get() = style != null

    companion object {
        val OFF: Layer = Layer()
    }
}
