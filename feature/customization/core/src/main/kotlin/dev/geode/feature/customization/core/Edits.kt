package dev.geode.feature.customization.core

import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.ExtraKind
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

    /**
     * Moves to the Style [to]. Its own parts (Extras, Macros, Reactions, Modulators) start from its
     * defaults. The Shared core starts from its defaults too, except where the person changed a value
     * away from the old Style's ([from]) default: that change carries over, as do the Sound trims.
     */
    fun switchStyle(
        customization: Customization,
        from: StyleManifest,
        to: StyleManifest,
    ): Customization {
        val oldDefaults = Defaults.customization(from).shared
        val changed = customization.shared.filter { (property, value) -> oldDefaults[property] != value }
        val fresh = Defaults.customization(to)
        return fresh.copy(shared = fresh.shared + changed, signals = customization.signals)
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
