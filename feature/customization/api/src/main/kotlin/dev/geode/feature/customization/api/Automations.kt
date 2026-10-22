package dev.geode.feature.customization.api

import dev.geode.engine.types.Millis

/**
 * What starts an Automation's move, read only from what the live analysis has already heard (#41,
 * owner decisions 2026-10-22): no tempo is ever assumed, so a bar-timed trigger holds until the live
 * analysis reports a tempo.
 */
sealed interface Trigger {
    /** A big moment: a Surge or a Change reaching [threshold] (0..1), once each time it rises past it. */
    data class BigMoments(
        val threshold: Float = DEFAULT_THRESHOLD,
    ) : Trigger {
        companion object {
            const val DEFAULT_THRESHOLD: Float = 0.6f

            /** The lowest threshold: below it, a big moment would never fall back to fire again. */
            const val MIN_THRESHOLD: Float = 0.05f
        }
    }

    /** Every [bars] bars of four beats, on the beats the live analysis reports. */
    data class EveryBars(
        val bars: Int = DEFAULT_BARS,
    ) : Trigger {
        companion object {
            const val DEFAULT_BARS: Int = 8
        }
    }
}

/** When an Automation moves: on its [trigger], and never sooner than [spacing] after its last move. */
data class Cadence(
    val trigger: Trigger = Trigger.BigMoments(),
    val spacing: Millis = DEFAULT_SPACING,
) {
    companion object {
        val DEFAULT_SPACING: Millis = Millis(10_000)
        val MAX_SPACING: Millis = Millis(300_000)
    }
}

/** Where Style switching picks the next Style from (a setting). */
enum class StylePool {
    /** The Visualizer styles: the Styles of the Visuals feature, handed in by `:app`, in their order. */
    VISUALIZER,

    /** The app's picks: every built-in Style in a seeded shuffle, none again until all have played. */
    PICKS,

    /** The Visualizer styles first, then the app's picks of the rest. */
    BOTH,
}

/**
 * Switches Style by itself at its [cadence], from its [pool], through a smooth crossfade of
 * [transition] that keeps the No-flash rule. [seed] fixes the app's picks' shuffle, so a Render repeats.
 */
data class StyleSwitching(
    val cadence: Cadence = Cadence(),
    val pool: StylePool = StylePool.BOTH,
    val seed: Long = 0L,
    val transition: Millis = DEFAULT_TRANSITION,
) {
    companion object {
        val MIN_TRANSITION: Millis = Millis(1_000)
        val DEFAULT_TRANSITION: Millis = Millis(3_000)
        val MAX_TRANSITION: Millis = Millis(10_000)
    }
}

/** Moves to the next palette at its own [cadence], crossfading over [fade]: never a cut. */
data class PaletteChanges(
    val cadence: Cadence = Cadence(Trigger.EveryBars()),
    val fade: Millis = DEFAULT_FADE,
) {
    companion object {
        val MIN_FADE: Millis = Millis(1_000)
        val DEFAULT_FADE: Millis = Millis(2_000)
        val MAX_FADE: Millis = Millis(10_000)
    }
}

/** How often a Camera move comes round, in beats. */
enum class BeatSpan(
    val beats: Int,
) {
    ONE(1),
    TWO(2),
    FOUR(4),
}

/**
 * The Camera moves on the beat (#41): each moves one or more Camera properties ([moves], each a
 * property and its direction), and swings the [alternating] ones the other way every other time.
 */
enum class CameraMoveKind(
    val moves: Map<SharedProperty, Float>,
    val alternating: Set<SharedProperty> = emptySet(),
) {
    PUSH_IN(mapOf(SharedProperty.PUSH_IN to 1f)),
    PULL_OUT(mapOf(SharedProperty.ZOOM to -1f)),
    ZOOM_PUNCH(mapOf(SharedProperty.ZOOM to 1f)),
    ORBIT(mapOf(SharedProperty.ORBIT_YAW to 1f), setOf(SharedProperty.ORBIT_YAW)),
    ROLL(mapOf(SharedProperty.ROTATION to 1f), setOf(SharedProperty.ROTATION)),
    TRAVEL_CURVE(mapOf(SharedProperty.DRIFT_X to 1f, SharedProperty.TRAVEL to 1f), setOf(SharedProperty.DRIFT_X)),
    DOLLY(mapOf(SharedProperty.TRAVEL to 1f)),
    TILT(mapOf(SharedProperty.ORBIT_PITCH to 1f), setOf(SharedProperty.ORBIT_PITCH)),
    PAN(mapOf(SharedProperty.DRIFT_X to 1f), setOf(SharedProperty.DRIFT_X)),
    SWAY(mapOf(SharedProperty.SWAY to 1f)),
    SHAKE(mapOf(SharedProperty.SHAKE to 1f)),
}

/**
 * One Camera move on the beat: [kind], coming round [every] 1, 2 or 4 beats, at [strength] (a fraction
 * 0..1 of each property's range). It is eased over its whole span, so over at least one beat, and added
 * on top of the Camera settings.
 */
data class CameraMove(
    val kind: CameraMoveKind,
    val every: BeatSpan = BeatSpan.TWO,
    val strength: Float = DEFAULT_STRENGTH,
) {
    companion object {
        const val DEFAULT_STRENGTH: Float = 0.25f
    }
}

/** What one step of a [Lane] lasts. */
enum class StepLength(
    val beats: Int,
) {
    BEAT(1),
    BAR(4),
}

/**
 * A sequence lane (#41): each step sets every one of its [targets] (effects on or off, parameters,
 * Modulator fields) to its value in that step, a fraction 0..1 of the target's range, one step per
 * [step]. [steps] holds one list per step, with one value per target in the targets' order.
 */
data class Lane(
    val targets: List<Target>,
    val steps: List<List<Float>>,
    val step: StepLength = StepLength.BAR,
)

/**
 * What the Customization performs by itself over the music. Everything is off until the person turns
 * it on. There is no separate autopilot: a Macro drifts by itself only when a Modulator is linked to it.
 */
data class Automations(
    val style: StyleSwitching? = null,
    val palette: PaletteChanges? = null,
    val camera: List<CameraMove> = emptyList(),
    val lanes: List<Lane> = emptyList(),
) {
    companion object {
        val NONE: Automations = Automations()

        /** The most lanes, targets in one lane, and steps in one lane: the engine's room for them. */
        const val MAX_LANES: Int = 8
        const val MAX_LANE_TARGETS: Int = 4
        const val MAX_LANE_STEPS: Int = 16
    }
}
