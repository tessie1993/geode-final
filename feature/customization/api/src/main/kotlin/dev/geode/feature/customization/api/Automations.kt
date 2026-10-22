package dev.geode.feature.customization.api

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId

/** When an Automation acts, counted from what has already been heard. */
sealed interface Cadence {
    /** On the music's big moments: a surge or a structural change. */
    data object BigMoments : Cadence

    /** Every [bars] bars of four beats. */
    data class EveryBars(
        val bars: Int,
    ) : Cadence
}

/** Where Style switching picks the next Style from (a setting). */
enum class StylePool {
    /** The person's own list. */
    MINE,

    /** The app's picks. */
    PICKS,

    /** The person's list first, then the app's picks. */
    BOTH,
}

/**
 * Switches Style by itself at its [cadence], from its [pool] ([mine] is the person's list), through a
 * smooth crossfade of [transition] that keeps the No-flash rule.
 */
data class StyleSwitching(
    val cadence: Cadence = Cadence.BigMoments,
    val pool: StylePool = StylePool.BOTH,
    val mine: List<SceneId> = emptyList(),
    val transition: Millis = DEFAULT_TRANSITION,
) {
    companion object {
        val MIN_TRANSITION: Millis = Millis(1_000)
        val DEFAULT_TRANSITION: Millis = Millis(3_000)
        val MAX_TRANSITION: Millis = Millis(10_000)
    }
}

/** Moves to the next palette at its [cadence]. */
data class PaletteChanges(
    val cadence: Cadence = Cadence.EveryBars(DEFAULT_BARS),
) {
    companion object {
        const val DEFAULT_BARS: Int = 8
    }
}

/** A Camera move on the beat: [property] (a Camera property) pushed by [amount] of its range every [beats] beats, easing back. */
data class CameraMove(
    val property: SharedProperty,
    val amount: Float = DEFAULT_AMOUNT,
    val beats: Int = 1,
) {
    companion object {
        const val DEFAULT_AMOUNT: Float = 0.25f
    }
}

/**
 * Steps [target] (an effect, a parameter or a Modulator's field) through [steps], fractions 0..1 of
 * its range, one step every [beats] beats.
 */
data class Sequenced(
    val target: Target,
    val steps: List<Float>,
    val beats: Int = DEFAULT_BEATS,
) {
    companion object {
        const val DEFAULT_BEATS: Int = 4
    }
}

/**
 * An autopilot Macro: moves Macro [macro] around the value the person set, up to [width] either side,
 * one swing every [bars] bars. The person's touch sets the value it moves around.
 */
data class Autopilot(
    val macro: Int,
    val width: Float = DEFAULT_WIDTH,
    val bars: Int = DEFAULT_BARS,
) {
    companion object {
        const val DEFAULT_WIDTH: Float = 0.2f
        const val MAX_WIDTH: Float = 0.5f
        const val DEFAULT_BARS: Int = 8
    }
}

/** What the Customization performs by itself over the music. Everything is off until the person turns it on. */
data class Automations(
    val style: StyleSwitching? = null,
    val palette: PaletteChanges? = null,
    val camera: List<CameraMove> = emptyList(),
    val sequences: List<Sequenced> = emptyList(),
    val autopilots: List<Autopilot> = emptyList(),
) {
    companion object {
        val NONE: Automations = Automations()
    }
}
