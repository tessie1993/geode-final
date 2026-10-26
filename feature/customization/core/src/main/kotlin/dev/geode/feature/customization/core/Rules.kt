package dev.geode.feature.customization.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Automations
import dev.geode.feature.customization.api.Cadence
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.Lane
import dev.geode.feature.customization.api.Layer
import dev.geode.feature.customization.api.LayerLoop
import dev.geode.feature.customization.api.MACRO_COUNT
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.PaletteChanges
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SignalTrim
import dev.geode.feature.customization.api.StyleFamily
import dev.geode.feature.customization.api.StyleManifest
import dev.geode.feature.customization.api.StyleSwitching
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.Trigger
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
     * [automations] as [customization] can run them:
     * - a cadence's threshold within [Trigger.BigMoments.MIN_THRESHOLD]..1, its bars within
     *   1..[MAX_AUTOMATION_BARS], its spacing within 0..[Cadence.MAX_SPACING];
     * - Style switching's transition and the palette fade within their ranges;
     * - Camera moves at a strength in 0..1, one of each kind;
     * - lanes on targets that exist, each target in one lane only, at most [Automations.MAX_LANES]
     *   lanes of [Automations.MAX_LANE_TARGETS] targets and [Automations.MAX_LANE_STEPS] steps, every
     *   step with one value 0..1 per target (a missing one is 0); a lane left with no target or no
     *   step is dropped.
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
                        transition = it.transition.coerceIn(StyleSwitching.MIN_TRANSITION, StyleSwitching.MAX_TRANSITION),
                    )
                },
            palette =
                automations.palette?.let {
                    it.copy(cadence = cadence(it.cadence), fade = it.fade.coerceIn(PaletteChanges.MIN_FADE, PaletteChanges.MAX_FADE))
                },
            camera = automations.camera.distinctBy { it.kind }.map { it.copy(strength = unit(it.strength)) },
            lanes = lanes(customization, automations.lanes),
        )

    private fun cadence(cadence: Cadence): Cadence =
        Cadence(
            trigger =
                when (val trigger = cadence.trigger) {
                    is Trigger.BigMoments -> Trigger.BigMoments(threshold(trigger.threshold))
                    is Trigger.EveryBars -> Trigger.EveryBars(trigger.bars.coerceIn(1, MAX_AUTOMATION_BARS))
                },
            spacing = cadence.spacing.coerceIn(Millis.ZERO, Cadence.MAX_SPACING),
        )

    private fun threshold(threshold: Float): Float =
        if (threshold.isNaN()) Trigger.BigMoments.MIN_THRESHOLD else threshold.coerceIn(Trigger.BigMoments.MIN_THRESHOLD, 1f)

    private fun lanes(
        customization: Customization,
        lanes: List<Lane>,
    ): List<Lane> {
        val taken = mutableSetOf<Target>()
        return lanes
            .filter { it.steps.isNotEmpty() }
            .map { lane ->
                val kept = mutableListOf<Int>()
                for (at in lane.targets.indices) {
                    val target = lane.targets[at]
                    if (kept.size < Automations.MAX_LANE_TARGETS && target !in taken && exists(customization, target)) {
                        kept += at
                        taken += target
                    }
                }
                lane.copy(
                    targets = kept.map { lane.targets[it] },
                    steps = lane.steps.take(Automations.MAX_LANE_STEPS).map { values -> kept.map { unit(values.getOrElse(it) { 0f }) } },
                )
            }.filter { it.targets.isNotEmpty() }
            .take(Automations.MAX_LANES)
    }

    /**
     * Whether [manifest]'s Style can be Layer 2 over the Style [over]: not the same Style, and not
     * MilkDrop, whose one engine is the MilkDrop preset the person picked.
     */
    fun canBeLayer(
        manifest: StyleManifest,
        over: SceneId,
    ): Boolean = manifest.family != StyleFamily.MILKDROP && manifest.id != over

    /** [loop] with its depth in 0..1; a loop that moves nothing is no loop. */
    fun layerLoop(loop: LayerLoop?): LayerLoop? =
        when {
            loop == null || loop.targets.isEmpty() -> null
            else -> loop.copy(depth = unit(loop.depth))
        }

    /**
     * [layer] as it can be laid over the Style [over] with the Styles in [styles]: its loop in range, and
     * off when its Style is unknown or cannot be Layer 2 there.
     */
    fun layer(
        layer: Layer,
        over: SceneId,
        styles: StyleCatalogue,
    ): Layer {
        val style = layer.style?.takeIf { id -> styles[id]?.let { canBeLayer(it, over) } == true }
        return layer.copy(style = style, loop = layerLoop(layer.loop))
    }

    /** [customization] without the Reactions, Macro links and lane targets whose source or target no longer exists. */
    fun prune(customization: Customization): Customization =
        customization.copy(
            reactions = customization.reactions.filter { exists(customization, it.source) && exists(customization, it.target) },
            macros = customization.macros.map { macro -> macro.copy(links = macro.links.filter { exists(customization, it.target) }) },
            automations = automations(customization, customization.automations),
        )
}
