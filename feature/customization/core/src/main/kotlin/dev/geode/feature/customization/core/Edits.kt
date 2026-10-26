package dev.geode.feature.customization.core

import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Automations
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.ExtraKind
import dev.geode.feature.customization.api.LayerBlend
import dev.geode.feature.customization.api.LayerInfluence
import dev.geode.feature.customization.api.LayerLoop
import dev.geode.feature.customization.api.Macro
import dev.geode.feature.customization.api.MacroLink
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionId
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SignalTrim
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.StartMode
import dev.geode.feature.customization.api.StyleManifest
import dev.geode.feature.customization.api.Target

/**
 * Every edit of a Customization, as a pure function. An edit that makes no sense (an Extra the
 * Style lacks, a link to nothing, an unknown id) returns the Customization unchanged, which the
 * reducer then treats as no edit at all.
 */
object Edits {
    fun setShared(
        customization: Customization,
        property: SharedProperty,
        value: Float,
    ): Customization = customization.copy(shared = customization.shared + (property to Rules.shared(property, value)))

    fun setExtra(
        customization: Customization,
        kind: ExtraKind,
        value: Float,
    ): Customization =
        if (kind in customization.extras) {
            customization.copy(extras = customization.extras + (kind to Rules.unit(value)))
        } else {
            customization
        }

    fun setMacro(
        customization: Customization,
        index: Int,
        value: Float,
    ): Customization = macro(customization, index) { it.copy(value = Rules.unit(value)) }

    fun renameMacro(
        customization: Customization,
        index: Int,
        name: String,
    ): Customization {
        val trimmed = name.trim().take(MAX_MACRO_NAME)
        return if (trimmed.isEmpty()) customization else macro(customization, index) { it.copy(name = trimmed) }
    }

    fun clearMacro(
        customization: Customization,
        index: Int,
    ): Customization = macro(customization, index) { it.copy(links = emptyList()) }

    /** Links a Macro to [link]'s target, replacing an existing link to it. A Macro never links to itself. */
    fun linkMacro(
        customization: Customization,
        index: Int,
        link: MacroLink,
    ): Customization {
        if (!Rules.exists(customization, link.target) || link.target == Target.MacroSlot(index)) return customization
        val clean = MacroLink(link.target, Rules.unit(link.low), Rules.unit(link.high))
        return macro(customization, index) { macro -> macro.copy(links = macro.links.filterNot { it.target == link.target } + clean) }
    }

    fun unlinkMacro(
        customization: Customization,
        index: Int,
        target: Target,
    ): Customization = macro(customization, index) { macro -> macro.copy(links = macro.links.filterNot { it.target == target }) }

    fun addReaction(
        customization: Customization,
        reaction: Reaction,
    ): Customization {
        if (!linkable(customization, reaction)) return customization
        val fresh = ReactionId((customization.reactions.maxOfOrNull { it.id.value } ?: 0) + 1)
        return customization.copy(reactions = customization.reactions + Rules.reaction(reaction.copy(id = fresh)))
    }

    fun updateReaction(
        customization: Customization,
        reaction: Reaction,
    ): Customization {
        if (customization.reactions.none { it.id == reaction.id } || !linkable(customization, reaction)) return customization
        val clean = Rules.reaction(reaction)
        return customization.copy(reactions = customization.reactions.map { if (it.id == reaction.id) clean else it })
    }

    fun removeReaction(
        customization: Customization,
        id: ReactionId,
    ): Customization = customization.copy(reactions = customization.reactions.filterNot { it.id == id })

    fun addModulator(
        customization: Customization,
        modulator: Modulator,
    ): Customization {
        val fresh = ModulatorId((customization.modulators.maxOfOrNull { it.id.value } ?: 0) + 1)
        return customization.copy(modulators = customization.modulators + Rules.modulator(modulator.copy(id = fresh)))
    }

    fun updateModulator(
        customization: Customization,
        modulator: Modulator,
    ): Customization {
        if (customization.modulators.none { it.id == modulator.id }) return customization
        val clean = Rules.modulator(modulator)
        return customization.copy(modulators = customization.modulators.map { if (it.id == modulator.id) clean else it })
    }

    /** Removes the Modulator and every Reaction and Macro link that used it. */
    fun removeModulator(
        customization: Customization,
        id: ModulatorId,
    ): Customization = Rules.prune(customization.copy(modulators = customization.modulators.filterNot { it.id == id }))

    /** Plays or stops a Modulator started by hand; others are not touched. */
    fun playModulator(
        customization: Customization,
        id: ModulatorId,
        playing: Boolean,
    ): Customization =
        customization.copy(
            modulators =
                customization.modulators.map { modulator ->
                    if (modulator.id == id && modulator.start == StartMode.ByHand) modulator.copy(playing = playing) else modulator
                },
        )

    fun trimSignal(
        customization: Customization,
        signal: SoundSignal,
        trim: SignalTrim,
    ): Customization = customization.copy(signals = customization.signals + (signal to Rules.trim(trim)))

    /** The Automations replaced by [automations], as [Rules.automations] lets them run. */
    fun setAutomations(
        customization: Customization,
        automations: Automations,
    ): Customization = customization.copy(automations = Rules.automations(customization, automations))

    /**
     * Lays [style] over the current Style as Layer 2 ([styles] says what it is), or turns Layer 2 off
     * when null. A Style that cannot be Layer 2 there changes nothing.
     */
    fun chooseLayerStyle(
        customization: Customization,
        style: SceneId?,
        styles: StyleCatalogue,
    ): Customization {
        if (style == null) return customization.copy(layer = customization.layer.copy(style = null))
        val manifest = styles[style] ?: return customization
        if (!Rules.canBeLayer(manifest, customization.style)) return customization
        return customization.copy(layer = customization.layer.copy(style = style))
    }

    fun setLayerBlend(
        customization: Customization,
        blend: LayerBlend,
    ): Customization = customization.copy(layer = customization.layer.copy(blend = blend))

    fun setLayerInfluence(
        customization: Customization,
        influence: LayerInfluence,
    ): Customization = customization.copy(layer = customization.layer.copy(influence = influence))

    /** Layer 2's loop set to [loop] as [Rules.layerLoop] lets it run, or stopped when null. */
    fun setLayerLoop(
        customization: Customization,
        loop: LayerLoop?,
    ): Customization = customization.copy(layer = customization.layer.copy(loop = Rules.layerLoop(loop)))

    /** Back to the Style's own defaults ([manifest]); the Automations stay, as they do across a change of Style. */
    fun resetToStyle(
        customization: Customization,
        manifest: StyleManifest,
    ): Customization {
        val fresh = Defaults.customization(manifest)
        return fresh.copy(automations = Rules.automations(fresh, customization.automations))
    }

    /**
     * [target] back to the Style's own starting value ([manifest]): a Shared core property, an Extra
     * the Style has, or a Macro's value. A Modulator's field has no starting value of its own.
     */
    fun resetValue(
        customization: Customization,
        manifest: StyleManifest,
        target: Target,
    ): Customization {
        val start = Defaults.customization(manifest)
        return when (target) {
            is Target.Shared -> setShared(customization, target.property, start.shared.getValue(target.property))
            is Target.Extra -> start.extras[target.kind]?.let { setExtra(customization, target.kind, it) } ?: customization
            is Target.MacroSlot ->
                start.macros.getOrNull(target.index)?.let { setMacro(customization, target.index, it.value) } ?: customization
            is Target.ModulatorField -> customization
        }
    }

    /**
     * Moves to the Style [to]. Its own parts (Extras, Macros, Reactions, Modulators) start from its
     * defaults. The Shared core starts from its defaults too, except where the person changed a value
     * away from the old Style's ([from]) default: that change carries over, as do the Sound trims,
     * the Automations (less any lane target, an Extra or a Modulator, the new Style lacks) and Layer 2,
     * which turns off when it is the Style moved to (a Style cannot be its own Layer 2).
     */
    fun switchStyle(
        customization: Customization,
        from: StyleManifest,
        to: StyleManifest,
    ): Customization {
        val oldDefaults = Defaults.customization(from).shared
        val changed = customization.shared.filter { (property, value) -> oldDefaults[property] != value }
        val fresh = Defaults.customization(to)
        return fresh.copy(
            shared = fresh.shared + changed,
            signals = customization.signals,
            automations = Rules.automations(fresh, customization.automations),
            layer = if (customization.layer.style == to.id) customization.layer.copy(style = null) else customization.layer,
        )
    }

    private fun linkable(
        customization: Customization,
        reaction: Reaction,
    ): Boolean = Rules.exists(customization, reaction.source) && Rules.exists(customization, reaction.target)

    private fun macro(
        customization: Customization,
        index: Int,
        change: (Macro) -> Macro,
    ): Customization =
        if (index in customization.macros.indices) {
            customization.copy(macros = customization.macros.mapIndexed { at, macro -> if (at == index) change(macro) else macro })
        } else {
            customization
        }
}
