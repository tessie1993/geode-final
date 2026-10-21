package dev.geode.feature.customization.api

import dev.geode.engine.types.Millis

/** One Reaction, unique within a Customization. */
@JvmInline
value class ReactionId(
    val value: Int,
) {
    override fun toString(): String = "reaction $value"
}

/**
 * What a Reaction, a Modulator's link or a Macro link moves. Every target is driven as a fraction 0..1
 * of its own range, so one Reaction shape works on any of them.
 */
sealed interface Target {
    /** A property of the Shared core, Camera included. */
    data class Shared(
        val property: SharedProperty,
    ) : Target

    /** One of the current Style's Extras. */
    data class Extra(
        val kind: ExtraKind,
    ) : Target

    /** One of the 6 Macros, by its place 0 until [MACRO_COUNT]. */
    data class MacroSlot(
        val index: Int,
    ) : Target

    /** A Modulator's rate, depth or pattern. Loops between Modulators are allowed. */
    data class ModulatorField(
        val modulator: ModulatorId,
        val parameter: ModulatorParameter,
    ) : Target
}

/** What a Reaction listens to: a measured Sound signal, or a generated Modulator. */
sealed interface ReactionSource {
    data class Sound(
        val signal: SoundSignal,
    ) : ReactionSource

    data class Generated(
        val modulator: ModulatorId,
    ) : ReactionSource
}

/** How a Reaction bends its input before it reaches the target. */
enum class ResponseCurve {
    LINEAR,
    EXPONENTIAL,
    LOGARITHMIC,
    S_CURVE,
}

/**
 * A link from one [source] to one [target] (#11): an [amount] 0..1 and its direction ([invert]), rise
 * and fall ([rise], [fall]), a [curve], and the range [low]..[high] (fractions of the target's range)
 * it keeps the target within. The input, normalised to 0..1, is curved, scaled by [amount] and added
 * to the target's own value (taken away when inverted). There is no limit on how many a
 * Customization has.
 */
data class Reaction(
    val id: ReactionId,
    val source: ReactionSource,
    val target: Target,
    val amount: Float = 1f,
    val invert: Boolean = false,
    val rise: Millis = Millis.ZERO,
    val fall: Millis = Millis.ZERO,
    val low: Float = 0f,
    val high: Float = 1f,
    val curve: ResponseCurve = ResponseCurve.LINEAR,
)
