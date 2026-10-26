package dev.geode.feature.customization.core

import dev.geode.engine.types.Millis
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.MACRO_COUNT
import dev.geode.feature.customization.api.MACRO_REST
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SignalTrim
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.StartMode
import dev.geode.feature.customization.api.Target

/**
 * A Customization as the engine's ReactionGraph reads it (ADR-0002): the snapshot's `plan` field, in
 * the layout core/viz/include/geode/viz/PlanCodec.hpp writes down. The engine evaluates it every
 * frame; NativeCustomizationKonsistTest holds these sizes to the C++ ones. Past the engine's limits
 * the rest is left out.
 *
 * Every value is normalised (#54, owner decision 2026-10-22): a Shared core property goes as a
 * fraction 0..1 of its range ([fraction]), like the Extras and Macros, and the engine's Normaliser maps
 * it into the Style family's own range. The Automations (#41, PR #141) are not carried yet: their
 * slots stay empty until that model lands here.
 *
 * Layer 2 goes last: its Style, blend and influence. Its loop ([LayerLoops]) goes as one more Modulator
 * and its Reactions, after the person's own, which give up the last slots if the plan is full.
 */
object NativePlan {
    const val MAX_REACTIONS: Int = 128
    const val MAX_MODULATORS: Int = 16
    const val MAX_MACRO_LINKS: Int = 64
    const val MAX_AUTOMATIONS: Int = 48
    const val MAX_STEPS: Int = 16
    const val HEADER_FLOATS: Int = 5
    const val REACTION_FLOATS: Int = 12
    const val MODULATOR_FLOATS: Int = 16 + MAX_STEPS
    const val MACRO_LINK_FLOATS: Int = 6
    const val AUTOMATION_FLOATS: Int = 12 + MAX_STEPS

    /** Layer 2: its Style ([StyleHash], 0 when off), blend and influence. */
    const val LAYER_FLOATS: Int = 3

    /** An index no array has: the engine ignores a link through it. */
    const val NO_INDEX: Float = 255f

    // Target::Kind and Source::Kind in ReactionGraph.hpp.
    const val TARGET_SHARED: Float = 1f
    const val TARGET_EXTRA: Float = 2f
    const val TARGET_MACRO: Float = 3f
    const val TARGET_MODULATOR: Float = 4f
    const val SOURCE_MODULATOR: Float = 1f

    // ModulatorKind and StartMode in Modulators.hpp.
    const val KIND_HIT_ENVELOPE: Float = 1f
    const val KIND_STEP_SEQUENCER: Float = 2f
    const val KIND_RANDOM_DRIFT: Float = 3f
    const val START_ON_SOUND: Float = 1f
    const val START_BY_HAND: Float = 2f

    /** How many floats a plan is: PlanCodec.hpp's kPlanFloats. */
    val floatCount: Int =
        HEADER_FLOATS + SharedProperty.entries.size + ExtraKind.entries.size + MACRO_COUNT + 2 * SoundSignal.entries.size +
            MAX_REACTIONS * REACTION_FLOATS + MAX_MODULATORS * MODULATOR_FLOATS + MAX_MACRO_LINKS * MACRO_LINK_FLOATS +
            MAX_AUTOMATIONS * AUTOMATION_FLOATS + LAYER_FLOATS

    private const val MILLIS_PER_SECOND = 1000f

    /** [value] of [property] as a fraction 0..1 of its range; its default's fraction when not a number. */
    fun fraction(
        property: SharedProperty,
        value: Float,
    ): Float {
        val own = if (value.isNaN()) property.default else value
        return ((own - property.min) / (property.max - property.min)).coerceIn(0f, 1f)
    }

    private class Writer(
        size: Int,
    ) {
        val out = FloatArray(size)
        var at = 0

        fun put(value: Float) {
            out[at++] = value
        }

        fun put(flag: Boolean) = put(if (flag) 1f else 0f)

        fun put(millis: Millis) = put(millis.value / MILLIS_PER_SECOND)

        fun skipTo(position: Int) {
            at = position
        }
    }

    /** [customization] as the snapshot's `plan` floats. */
    fun encode(customization: Customization): List<Float> {
        val w = Writer(floatCount)
        val layer = customization.layer
        val loop = layer.loop?.takeIf { layer.on && it.targets.isNotEmpty() }
        val loopId = ModulatorId((customization.modulators.maxOfOrNull { it.id.value } ?: 0) + 1)
        val loopReactions = loop?.let { LayerLoops.reactions(it, loopId) }.orEmpty()
        val reactions = customization.reactions.take(MAX_REACTIONS - loopReactions.size) + loopReactions
        val modulators =
            if (loop == null) {
                customization.modulators.take(MAX_MODULATORS)
            } else {
                customization.modulators.take(MAX_MODULATORS - 1) + LayerLoops.modulator(loop, loopId)
            }
        val links =
            customization.macros
                .take(MACRO_COUNT)
                .withIndex()
                .flatMap { (index, macro) -> macro.links.map { index to it } }
                .take(MAX_MACRO_LINKS)
        val indexOf: Map<ModulatorId, Int> = modulators.withIndex().associate { (index, m) -> m.id to index }

        fun modulator(id: ModulatorId): Float = indexOf[id]?.toFloat() ?: NO_INDEX

        fun target(target: Target) {
            when (target) {
                is Target.Shared -> listOf(TARGET_SHARED, target.property.ordinal.toFloat(), 0f)
                is Target.Extra -> listOf(TARGET_EXTRA, target.kind.ordinal.toFloat(), 0f)
                is Target.MacroSlot -> listOf(TARGET_MACRO, target.index.toFloat(), 0f)
                is Target.ModulatorField -> listOf(TARGET_MODULATOR, modulator(target.modulator), target.parameter.ordinal.toFloat())
            }.forEach(w::put)
        }

        w.put(true)
        w.put(reactions.size.toFloat())
        w.put(modulators.size.toFloat())
        w.put(links.size.toFloat())
        w.put(0f)
        SharedProperty.entries.forEach { w.put(fraction(it, customization.shared[it] ?: it.default)) }
        ExtraKind.entries.forEach { w.put(customization.extras[it] ?: 0f) }
        repeat(MACRO_COUNT) { w.put(customization.macros.getOrNull(it)?.value ?: MACRO_REST) }
        SoundSignal.entries.forEach {
            val trim = customization.signals[it] ?: SignalTrim.NEUTRAL
            w.put(trim.gain)
            w.put(trim.floor)
        }

        val reactionsAt = w.at
        reactions.forEachIndexed { i, r ->
            w.skipTo(reactionsAt + i * REACTION_FLOATS)
            when (val source = r.source) {
                is ReactionSource.Sound -> listOf(0f, source.signal.ordinal.toFloat())
                is ReactionSource.Generated -> listOf(SOURCE_MODULATOR, modulator(source.modulator))
            }.forEach(w::put)
            target(r.target)
            w.put(r.amount)
            w.put(r.invert)
            w.put(r.low)
            w.put(r.high)
            w.put(r.curve.ordinal.toFloat())
            w.put(r.rise)
            w.put(r.fall)
        }

        val modulatorsAt = reactionsAt + MAX_REACTIONS * REACTION_FLOATS
        modulators.forEachIndexed { i, m ->
            val at = modulatorsAt + i * MODULATOR_FLOATS
            val shape = m.shape
            w.skipTo(at)
            when (shape) {
                is ModulatorShape.Lfo -> {
                    w.put(0f)
                    w.put(shape.wave.ordinal.toFloat())
                    w.put(shape.period)
                    w.put(shape.beatSync)
                }
                is ModulatorShape.HitEnvelope -> {
                    w.put(KIND_HIT_ENVELOPE)
                    w.skipTo(at + 4)
                    w.put(shape.signal.ordinal.toFloat())
                    w.put(shape.attack)
                    w.put(shape.release)
                    w.put(shape.threshold)
                }
                is ModulatorShape.StepSequencer -> {
                    w.put(KIND_STEP_SEQUENCER)
                    w.skipTo(at + 3)
                    w.put(shape.beatSync)
                    w.skipTo(at + 8)
                    val steps = shape.steps.take(MAX_STEPS)
                    steps.forEach(w::put)
                    w.skipTo(at + 8 + MAX_STEPS)
                    w.put(steps.size.toFloat())
                    w.put(shape.stepLength)
                }
                is ModulatorShape.RandomDrift -> {
                    w.put(KIND_RANDOM_DRIFT)
                    w.skipTo(at + 2)
                    w.put(shape.period)
                }
            }
            w.skipTo(at + 10 + MAX_STEPS)
            when (val start = m.start) {
                StartMode.WhilePlaying -> listOf(0f, 0f)
                is StartMode.OnSound -> listOf(START_ON_SOUND, start.signal.ordinal.toFloat())
                StartMode.ByHand -> listOf(START_BY_HAND, 0f)
            }.forEach(w::put)
            w.put(m.playing)
            w.put(m.rate)
            w.put(m.depth)
            w.put(m.id.value.toFloat())
        }

        val linksAt = modulatorsAt + MAX_MODULATORS * MODULATOR_FLOATS
        links.forEachIndexed { i, (macro, link) ->
            w.skipTo(linksAt + i * MACRO_LINK_FLOATS)
            w.put(macro.toFloat())
            target(link.target)
            w.put(link.low)
            w.put(link.high)
        }

        w.skipTo(linksAt + MAX_MACRO_LINKS * MACRO_LINK_FLOATS + MAX_AUTOMATIONS * AUTOMATION_FLOATS)
        w.put(layer.style?.let { StyleHash.of(it).toFloat() } ?: 0f)
        w.put(layer.blend.ordinal.toFloat())
        w.put(layer.influence.ordinal.toFloat())
        return w.out.asList()
    }
}
