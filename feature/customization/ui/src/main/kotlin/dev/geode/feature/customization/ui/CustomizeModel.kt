package dev.geode.feature.customization.ui

import androidx.compose.runtime.Immutable
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.CustomizationState
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.MacroLink
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionId
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.SharedGroup
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.ValueKind

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
    )

/** One Style the look picker strip offers. Which Styles, and their names, come from the place that opens Customize. */
@Immutable
data class LookChip(
    val style: SceneId,
    val name: String,
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

/** One of the 6 Macro knobs: its [value], what it moves ([links]), and the sources that drive it ([drivers]). */
@Immutable
data class MacroKnob(
    val index: Int,
    val name: String,
    val value: Float,
    val links: List<MacroLink>,
    val drivers: List<ReactionSource>,
    val live: Float?,
)

/** An on/off effect of the basic view. */
@Immutable
data class EffectToggle(
    val property: SharedProperty,
    val on: Boolean,
)

/**
 * One slider of the Advanced view: a Shared core property in its own units ([min]..[max]), or an
 * Extra as 0..1. [linked] says whether a Reaction moves it.
 */
@Immutable
data class SliderRow(
    val target: Target,
    val value: Float,
    val min: Float,
    val max: Float,
    val kind: ValueKind,
    val linked: Boolean,
)

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
 * Everything the Customize panel draws (ADR-0003: an immutable screen model), for the basic view and
 * the Advanced view alike. Which view is open is App UI state the screen keeps itself.
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
) {
    fun isCurrent(look: LookChip): Boolean = look.style == style
}

/**
 * Customize's model of this state. [looks] and [presets] are offered by the place that opens
 * Customize; [live] carries the engine's live values when there are any.
 */
fun CustomizationState.toCustomizeModel(
    looks: List<LookChip> = emptyList(),
    presets: List<PresetChip> = emptyList(),
    live: LiveMeters = LiveMeters.NONE,
): CustomizeModel {
    val customization = current
    val reactions = customization.reactions
    val linkedTargets = reactions.map { it.target }.toSet()
    fun sharedValue(property: SharedProperty) = customization.shared[property] ?: property.default
    fun sharedRow(property: SharedProperty) =
        Target.Shared(property).let { target ->
            SliderRow(target, sharedValue(property), property.min, property.max, property.kind, target in linkedTargets)
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
                )
            },
        effects = EFFECT_TOGGLES.map { EffectToggle(it, sharedValue(it) > it.min) },
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
    )
}
