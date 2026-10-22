package dev.geode.feature.customization.core

import dev.geode.engine.types.Millis
import dev.geode.feature.customization.api.Automations
import dev.geode.feature.customization.api.Autopilot
import dev.geode.feature.customization.api.Cadence
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.MACRO_COUNT
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.SharedGroup
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SignalTrim
import dev.geode.feature.customization.api.StyleSwitching
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.ValueKind
import kotlin.math.roundToInt

/**
 * The shortest rise a Reaction on a [SharedProperty.luminous] property may have. Beat pulses get a
 * rise time instead of snapping (#11, No-flash rule); the engine's limiter still bounds the result.
 */
val NO_FLASH_MIN_RISE = Millis(150)

/** A switch reads as on from this value up. */
const val SWITCH_ON: Float = 0.5f

/** The longest name a Macro may have. */
const val MAX_MACRO_NAME: Int = 24

/** The most bars an Automation may wait between moves. */
const val MAX_AUTOMATION_BARS: Int = 64

/** The most beats an Automation may wait between moves. */
const val MAX_AUTOMATION_BEATS: Int = 16

/** The rules every value in a Customization keeps: ranges, steps, switches and valid links. */
object Rules {
    /** [value] brought into [property]'s range, rounded when stepped, 0 or 1 when a switch. */
    fun shared(
        property: SharedProperty,
        value: Float,
    ): Float {
        if (value.isNaN()) return property.default
        val inRange = value.coerceIn(property.min, property.max)
        return when (property.kind) {
            ValueKind.CONTINUOUS -> inRange
            ValueKind.STEPPED -> inRange.roundToInt().toFloat()
            ValueKind.SWITCH -> if (inRange >= SWITCH_ON) 1f else 0f
        }
    }

    /** [value] brought into 0..1. */
    fun unit(value: Float): Float = if (value.isNaN()) 0f else value.coerceIn(0f, 1f)

    /**
     * [reaction] with its amount and range in 0..1, the range in order, no negative times, and the
     * No-flash rise on luminous targets.
     */
    fun reaction(reaction: Reaction): Reaction {
        val low = unit(reaction.low)
        val high = unit(reaction.high)
        val rise = maxOf(reaction.rise, Millis.ZERO)
        return reaction.copy(
            amount = unit(reaction.amount),
            low = minOf(low, high),
            high = maxOf(low, high),
            rise = if (luminous(reaction.target)) maxOf(rise, NO_FLASH_MIN_RISE) else rise,
            fall = maxOf(reaction.fall, Millis.ZERO),
        )
    }

    /** [modulator] with its rate, depth and steps in range. */
    fun modulator(modulator: Modulator): Modulator {
        val shape = modulator.shape
        return modulator.copy(
            rate = if (modulator.rate.isNaN()) Modulator.DEFAULT_RATE else modulator.rate.coerceIn(Modulator.MIN_RATE, Modulator.MAX_RATE),
            depth = unit(modulator.depth),
            shape = if (shape is ModulatorShape.StepSequencer) shape.copy(steps = shape.steps.map(::unit)) else shape,
        )
    }

    /** [trim] with its gain and floor in range. */
    fun trim(trim: SignalTrim): SignalTrim =
        SignalTrim(
            gain = if (trim.gain.isNaN()) SignalTrim.NEUTRAL_GAIN else trim.gain.coerceIn(SignalTrim.MIN_GAIN, SignalTrim.MAX_GAIN),
            floor = if (trim.floor.isNaN()) 0f else trim.floor.coerceIn(0f, SignalTrim.MAX_FLOOR),
        )

    /** Whether [target] moves overall brightness. */
    fun luminous(target: Target): Boolean = target is Target.Shared && target.property.luminous

    /** Whether [target] exists in [customization]. */
    fun exists(
        customization: Customization,
        target: Target,
    ): Boolean =
        when (target) {
            is Target.Shared -> true
            is Target.Extra -> target.kind in customization.extras
            is Target.MacroSlot -> target.index in 0 until MACRO_COUNT
            is Target.ModulatorField -> customization.modulators.any { it.id == target.modulator }
        }

    /** Whether [source] exists in [customization]. */
    fun exists(
        customization: Customization,
        source: ReactionSource,
    ): Boolean =
        when (source) {
            is ReactionSource.Sound -> true
            is ReactionSource.Generated -> customization.modulators.any { it.id == source.modulator }
        }

    /**
     * [automations] as [customization] can run them: waits of at least one bar or beat and at most
     * [MAX_AUTOMATION_BARS] or [MAX_AUTOMATION_BEATS], a transition within its range, amounts in 0..1,
     * Camera moves on Camera properties only, sequences with steps on targets that exist, autopilots on
     * real Macros, and at most one of each per property, target or Macro.
     */
    fun automations(
        customization: Customization,
        automations: Automations,
    ): Automations =
        Automations(
            style =
                automations.style?.let {
                    it.copy(
                        cadence = cadence(it.cadence),
                        mine = it.mine.distinct(),
                        transition = it.transition.coerceIn(StyleSwitching.MIN_TRANSITION, StyleSwitching.MAX_TRANSITION),
                    )
                },
            palette = automations.palette?.let { it.copy(cadence = cadence(it.cadence)) },
            camera =
                automations.camera
                    .filter { it.property.group == SharedGroup.CAMERA }
                    .distinctBy { it.property }
                    .map { it.copy(amount = unit(it.amount), beats = it.beats.coerceIn(1, MAX_AUTOMATION_BEATS)) },
            sequences =
                automations.sequences
                    .filter { it.steps.isNotEmpty() && exists(customization, it.target) }
                    .distinctBy { it.target }
                    .map { it.copy(steps = it.steps.map(::unit), beats = it.beats.coerceIn(1, MAX_AUTOMATION_BEATS)) },
            autopilots =
                automations.autopilots
                    .filter { it.macro in 0 until MACRO_COUNT }
                    .distinctBy { it.macro }
                    .map {
                        it.copy(
                            width = if (it.width.isNaN()) 0f else it.width.coerceIn(0f, Autopilot.MAX_WIDTH),
                            bars = it.bars.coerceIn(1, MAX_AUTOMATION_BARS),
                        )
                    },
        )

    private fun cadence(cadence: Cadence): Cadence =
        when (cadence) {
            Cadence.BigMoments -> cadence
            is Cadence.EveryBars -> Cadence.EveryBars(cadence.bars.coerceIn(1, MAX_AUTOMATION_BARS))
        }

    /** [customization] without the Reactions, Macro links and sequences whose source or target no longer exists. */
    fun prune(customization: Customization): Customization =
        customization.copy(
            reactions = customization.reactions.filter { exists(customization, it.source) && exists(customization, it.target) },
            macros = customization.macros.map { macro -> macro.copy(links = macro.links.filter { exists(customization, it.target) }) },
            automations =
                customization.automations.copy(
                    sequences = customization.automations.sequences.filter { exists(customization, it.target) },
                ),
        )
}
