package dev.geode.feature.customization.ui

import androidx.compose.runtime.Immutable
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Automations
import dev.geode.feature.customization.api.CompareSlot
import dev.geode.feature.customization.api.CustomizationState
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.Layer
import dev.geode.feature.customization.api.MacroLink
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionId
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.SharedGroup
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SignalTrim
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.ValueKind
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The Shared core effects the basic view offers as on/off toggles (#12): each is on while it is above
 * its minimum.
 */
internal val EFFECT_TOGGLES: List<SharedProperty> =
    listOf(
        SharedProperty.KALEIDOSCOPE,
        SharedProperty.MIRROR,
        SharedProperty.TRAILS,
        SharedProperty.GLITCH,
        SharedProperty.PARTICLES,
        SharedProperty.DUOTONE,
        SharedProperty.COLOUR_CYCLE,
        SharedProperty.ENDLESS_ZOOM,
        SharedProperty.SOLARIZE,
        SharedProperty.INVERT,
        SharedProperty.ANAGLYPH,
    )

/**
 * One Style the look picker strip offers. Which Styles, and their names, come from the place that opens
 * Customize, which also says whether the Style [canBeLayer]: MilkDrop cannot, its one engine being the
 * MilkDrop preset the person picked.
 */
@Immutable
data class LookChip(
    val style: SceneId,
    val name: String,
    val canBeLayer: Boolean = true,
)

/** One saved preset the preset row offers for this Style. */
@Immutable
data class PresetChip(
    val id: PresetId,
    val name: String,
)

/**
 * Live values measured by the engine as the music plays, for the knobs' rings and the links' meters:
 * [macros] by Macro place, [reactions] by Reaction, each 0..1. Empty until an engine source feeds it;
 * the screens then show the set values only.
 */
@Immutable
data class LiveMeters(
    val macros: Map<Int, Float> = emptyMap(),
    val reactions: Map<ReactionId, Float> = emptyMap(),
) {
    companion object {
        val NONE = LiveMeters()
    }
}

/**
 * One of the 6 Macro knobs: its [value], what it moves ([links]), and the sources that drive it
 * ([drivers]). [ignored]: every link moves something the current Style does not use (#365).
 */
@Immutable
data class MacroKnob(
    val index: Int,
    val name: String,
    val value: Float,
    val links: List<MacroLink>,
    val drivers: List<ReactionSource>,
    val live: Float?,
    val ignored: Boolean = false,
)

/** An on/off effect of the basic view; [ignored] when the current Style does not use it (#365). */
@Immutable
data class EffectToggle(
    val property: SharedProperty,
    val on: Boolean,
    val ignored: Boolean = false,
)

/**
 * The multiplicative controls (#285): their sliders move by ratios, as MilkDrop steps them, so each
 * default sits near the middle of the travel and every stretch of it feels the same.
 */
internal val RATIO_PROPERTIES: Set<SharedProperty> =
    setOf(
        SharedProperty.ZOOM,
        SharedProperty.DIVE_SPEED,
        SharedProperty.CYCLE_SPEED,
        SharedProperty.SPEED,
        SharedProperty.AUDIO_DRIVE,
        SharedProperty.PARTICLE_SIZE,
        SharedProperty.GAMMA,
    )

/**
 * One slider of the Advanced view: a Shared core property in its own units ([min]..[max]), or an
 * Extra as 0..1. [linked] says whether a Reaction moves it; [ratio] that it moves by ratios;
 * [ignored] whether the current Style leaves it alone (#365), which greys it out.
 */
@Immutable
data class SliderRow(
    val target: Target,
    val value: Float,
    val min: Float,
    val max: Float,
    val kind: ValueKind,
    val linked: Boolean,
    val ratio: Boolean = false,
    val ignored: Boolean = false,
) {
    /**
     * Where [value] sits along the slider, 0..1 of its travel: by the ratio of [value] to [min] on a
     * [ratio] row (a log curve), evenly otherwise.
     */
    fun position(value: Float): Float {
        val clamped = value.coerceIn(min, max)
        val at = if (ratio) ln(clamped / min) / ln(max / min) else (clamped - min) / (max - min)
        return at.coerceIn(0f, 1f)
    }

    /** The value at [position] (0..1) along the slider, the reverse of [position]; a whole number on a stepped row. */
    fun valueAt(position: Float): Float {
        val at = position.coerceIn(0f, 1f)
        val value = if (ratio) min * (max / min).pow(at) else min + at * (max - min)
        return if (kind == ValueKind.STEPPED) value.roundToInt().toFloat() else value.coerceIn(min, max)
    }
}

/** One Sound signal's trim (#301): how strongly that signal drives every Reaction, in every Style. */
@Immutable
data class TrimRow(
    val signal: SoundSignal,
    val trim: SignalTrim,
)

/** The signals Customize offers a trim for, in the order it lists them: bass, mid, treble and beat. */
val TRIMMED_SIGNALS: List<SoundSignal> = listOf(SoundSignal.BASS, SoundSignal.MID, SoundSignal.TREBLE, SoundSignal.BEAT)

/** One link of the link list, with its [live] meter. */
@Immutable
data class LinkRow(
    val reaction: Reaction,
    val live: Float?,
)

/** Every link that moves one [target]. */
@Immutable
data class ReactionGroup(
    val target: Target,
    val links: List<LinkRow>,
)

/** One Modulator, and whether a Reaction listens to it: one that nothing listens to does not run. */
@Immutable
data class ModulatorRow(
    val modulator: Modulator,
    val linked: Boolean,
)

/**
 * Everything the Customize panel and [ModulatorsAutomationScreen] draw (ADR-0003: an immutable screen
 * model), for the basic view and the Advanced view alike. Which view is open is App UI state the screen
 * keeps itself.
 */
@Immutable
data class CustomizeModel(
    val style: SceneId,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val macros: List<MacroKnob>,
    val effects: List<EffectToggle>,
    val palette: Int,
    val looks: List<LookChip>,
    val presets: List<PresetChip>,
    val reactions: List<ReactionGroup>,
    val modulators: List<ModulatorRow>,
    val shared: Map<SharedGroup, List<SliderRow>>,
    val extras: List<SliderRow>,
    val sources: List<ReactionSource>,
    val targets: List<Target>,
    val automations: Automations,
    /** The A/B compare slots that keep a Customization to switch to. */
    val kept: Set<CompareSlot> = emptySet(),
    /** Layer 2, a second Style laid over this one; its opacity, influence and swap are in [shared]. */
    val layer: Layer = Layer.OFF,
    /** The trims of [TRIMMED_SIGNALS], in that order. */
    val trims: List<TrimRow> = emptyList(),
) {
    fun isCurrent(look: LookChip): Boolean = look.style == style

    /** The looks Layer 2 can show: every look but the Style itself and those that cannot be a layer. */
    val layerLooks: List<LookChip>
        get() = looks.filter { it.canBeLayer && it.style != style }

    /** Whether some look is left out of Layer 2's picker, which the Layers section then explains. */
    val someLookCannotBeLayer: Boolean
        get() = looks.any { !it.canBeLayer }

    /** The name Layer 2's Style is shown by, or null while Layer 2 is off. */
    val layerName: String?
        get() = layer.style?.let { id -> looks.firstOrNull { it.style == id }?.name ?: id.value }
}

/**
 * Customize's model of this state. [looks] and [presets] are offered by the place that opens
 * Customize; [live] carries the engine's live values when there are any; [ignored] are the controls
 * the current Style leaves alone, greyed out (#365), which the opening place works out from the
 * Style's family.
 */
fun CustomizationState.toCustomizeModel(
    looks: List<LookChip> = emptyList(),
    presets: List<PresetChip> = emptyList(),
    live: LiveMeters = LiveMeters.NONE,
    ignored: Set<Target> = emptySet(),
): CustomizeModel {
    val customization = current
    val reactions = customization.reactions
    val linkedTargets = reactions.map { it.target }.toSet()

    fun sharedValue(property: SharedProperty) = customization.shared[property] ?: property.default

    fun sharedRow(property: SharedProperty) =
        Target.Shared(property).let { target ->
            SliderRow(
                target,
                sharedValue(property),
                property.min,
                property.max,
                property.kind,
                target in linkedTargets,
                ratio = property in RATIO_PROPERTIES,
                ignored = target in ignored,
            )
        }
    val extraKinds = ExtraKind.entries.filter { it in customization.extras }
    val listeners = reactions.mapNotNull { (it.source as? ReactionSource.Generated)?.modulator }.toSet()
    return CustomizeModel(
        style = customization.style,
        canUndo = undo.isNotEmpty(),
        canRedo = redo.isNotEmpty(),
        macros =
            customization.macros.mapIndexed { index, macro ->
                MacroKnob(
                    index = index,
                    name = macro.name,
                    value = macro.value,
                    links = macro.links,
                    drivers = reactions.filter { it.target == Target.MacroSlot(index) }.map { it.source },
                    live = live.macros[index],
                    ignored = Target.MacroSlot(index) in ignored,
                )
            },
        effects = EFFECT_TOGGLES.map { EffectToggle(it, sharedValue(it) > it.min, ignored = Target.Shared(it) in ignored) },
        palette = sharedValue(SharedProperty.PALETTE).toInt(),
        looks = looks,
        presets = presets,
        reactions =
            reactions.groupBy { it.target }.map { (target, links) ->
                ReactionGroup(target, links.map { LinkRow(it, live.reactions[it.id]) })
            },
        modulators = customization.modulators.map { ModulatorRow(it, it.id in listeners) },
        shared = SharedGroup.entries.associateWith { group -> SharedProperty.entries.filter { it.group == group }.map(::sharedRow) },
        extras =
            extraKinds.map { kind ->
                val target = Target.Extra(kind)
                SliderRow(target, customization.extras.getValue(kind), 0f, 1f, ValueKind.CONTINUOUS, target in linkedTargets)
            },
        sources = SoundSignal.entries.map { ReactionSource.Sound(it) } + customization.modulators.map { ReactionSource.Generated(it.id) },
        targets =
            SharedProperty.entries.map { Target.Shared(it) } +
                extraKinds.map { Target.Extra(it) } +
                customization.macros.indices.map { Target.MacroSlot(it) } +
                customization.modulators.flatMap { modulator ->
                    ModulatorParameter.entries.map { Target.ModulatorField(modulator.id, it) }
                },
        automations = customization.automations,
        kept = compare.keys,
        layer = customization.layer,
        trims = TRIMMED_SIGNALS.map { TrimRow(it, customization.signals[it] ?: SignalTrim.NEUTRAL) },
    )
}
