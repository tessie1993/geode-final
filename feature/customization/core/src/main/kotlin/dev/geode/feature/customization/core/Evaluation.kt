package dev.geode.feature.customization.core

import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.MACRO_REST
import dev.geode.feature.customization.api.MacroLink
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.ResponseCurve
import dev.geode.feature.customization.api.SignalTrim
import dev.geode.feature.customization.api.StartMode
import kotlin.math.sqrt

/**
 * The reference meaning of the per-frame stages the engine runs in C++ (ADR-0002): the normaliser,
 * a Reaction's shape, a Macro link, and which Modulators run. Kotlin never runs these per frame; they
 * pin down what the snapshot means, and the native side is held to the same numbers.
 */
object Evaluation {
    /** The normaliser stage: [raw] scaled by the trim's gain, below its floor 0, and clamped to 0..1. */
    fun normalise(
        raw: Float,
        trim: SignalTrim,
    ): Float {
        val scaled = raw * trim.gain
        if (scaled.isNaN() || scaled <= trim.floor) return 0f
        return ((scaled - trim.floor) / (1f - trim.floor)).coerceIn(0f, 1f)
    }

    /** [input] (0..1) bent by [curve]. */
    fun curve(
        input: Float,
        curve: ResponseCurve,
    ): Float {
        val x = Rules.unit(input)
        return when (curve) {
            ResponseCurve.LINEAR -> x
            ResponseCurve.EXPONENTIAL -> x * x
            ResponseCurve.LOGARITHMIC -> sqrt(x)
            ResponseCurve.S_CURVE -> x * x * (S_CURVE_A - S_CURVE_B * x)
        }
    }

    /**
     * Where [reaction] puts its target (a fraction 0..1 of the target's range) when the target's own
     * value is [base] and the normalised, already risen-and-fallen input is [input]: the curved input
     * times the amount is added (or taken away when inverted), and the result kept in the range.
     */
    fun react(
        base: Float,
        input: Float,
        reaction: Reaction,
    ): Float {
        val push = curve(input, reaction.curve) * reaction.amount
        val moved = if (reaction.invert) base - push else base + push
        return moved.coerceIn(reaction.low, reaction.high)
    }

    /** Where a Macro at [value] puts [link]'s target whose own value is [base] (all fractions 0..1). */
    fun macro(
        base: Float,
        link: MacroLink,
        value: Float,
    ): Float {
        val v = Rules.unit(value)
        return if (v < MACRO_REST) {
            link.low + (base - link.low) * (v / MACRO_REST)
        } else {
            base + (link.high - base) * ((v - MACRO_REST) / (1f - MACRO_REST))
        }
    }

    /**
     * The Modulators that run: those some Reaction listens to, and of the ones started by hand only
     * those playing. An unlinked Modulator does not run.
     */
    fun running(customization: Customization): Set<ModulatorId> {
        val listened = customization.reactions.map { it.source }.filterIsInstance<ReactionSource.Generated>().map { it.modulator }.toSet()
        return customization.modulators
            .filter { it.id in listened && (it.start != StartMode.ByHand || it.playing) }
            .map { it.id }
            .toSet()
    }

    private const val S_CURVE_A = 3f
    private const val S_CURVE_B = 2f
}
