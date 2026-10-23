package dev.geode.feature.customization.api

import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.kernel.Feature

/**
 * Customization's public interface (ADR-0003, ADR-0005): the person's choices for how a Style looks
 * and how it reacts to sound. It replaces the flat `SceneParams` as the source of truth; the engine
 * is handed the result whenever it changes and evaluates Reactions per frame (ADR-0002).
 *
 * Customization is about the Visual only. Reduce motion does not apply to it (#11), and nothing here
 * is App UI.
 */
typealias CustomizationFeature = Feature<CustomizationState, CustomizationCommand, CustomizationFact>

/**
 * Everything the person chose for one Style.
 *
 * - [shared]: every Shared core property, in its own units.
 * - [extras]: the Style's own Extras, each 0..1; only the kinds the Style declares.
 * - [macros]: always [MACRO_COUNT].
 * - [reactions] and [modulators]: as many as the person made.
 * - [signals]: the normaliser's trim for every Sound signal.
 * - [automations]: what it performs by itself over the music; they carry over to the next Style and
 *   are not saved in presets.
 */
data class Customization(
    val style: SceneId,
    val shared: Map<SharedProperty, Float>,
    val extras: Map<ExtraKind, Float>,
    val macros: List<Macro>,
    val reactions: List<Reaction>,
    val modulators: List<Modulator>,
    val signals: Map<SoundSignal, SignalTrim>,
    val automations: Automations = Automations.NONE,
)

/**
 * Customization's state: the [current] Customization and the session's unlimited undo and redo
 * history, the newest step last. The history survives Android killing the app (ADR-0004).
 *
 * Style switching's own bookkeeping sits beside it, outside the history: [visualizerStyles], the
 * Visualizer styles `:app` hands in ([CustomizationCommand.UseVisualizerStyles]), and [styleSwitches],
 * how many times it has switched, which says where the app's picks' shuffle has got to.
 */
data class CustomizationState(
    val current: Customization,
    val undo: List<Customization> = emptyList(),
    val redo: List<Customization> = emptyList(),
    val visualizerStyles: List<SceneId> = emptyList(),
    val styleSwitches: Int = 0,
)

/** The intents: what the outside may ask of Customization. Every edit can be undone. */
sealed interface CustomizationCommand {
    /** Switch to [style]: its own parts start from its defaults, the shared parts carry over. */
    data class ChooseStyle(
        val style: SceneId,
    ) : CustomizationCommand

    data class SetShared(
        val property: SharedProperty,
        val value: Float,
    ) : CustomizationCommand

    /** Sets an Extra (0..1); ignored if the current Style does not have it. */
    data class SetExtra(
        val kind: ExtraKind,
        val value: Float,
    ) : CustomizationCommand

    data class SetMacro(
        val index: Int,
        val value: Float,
    ) : CustomizationCommand

    data class RenameMacro(
        val index: Int,
        val name: String,
    ) : CustomizationCommand

    /** Removes every link of the Macro, so it can be rebuilt. */
    data class ClearMacro(
        val index: Int,
    ) : CustomizationCommand

    /** Adds [link] to the Macro, replacing its link to the same target if it has one. */
    data class LinkMacro(
        val index: Int,
        val link: MacroLink,
    ) : CustomizationCommand

    data class UnlinkMacro(
        val index: Int,
        val target: Target,
    ) : CustomizationCommand

    /** Adds [reaction]; the store gives it a fresh id, whatever id it carries. */
    data class AddReaction(
        val reaction: Reaction,
    ) : CustomizationCommand

    /** Replaces the Reaction with the same id. */
    data class UpdateReaction(
        val reaction: Reaction,
    ) : CustomizationCommand

    data class RemoveReaction(
        val id: ReactionId,
    ) : CustomizationCommand

    /** Adds [modulator]; the store gives it a fresh id. It stays idle until a Reaction listens to it. */
    data class AddModulator(
        val modulator: Modulator,
    ) : CustomizationCommand

    /** Replaces the Modulator with the same id. */
    data class UpdateModulator(
        val modulator: Modulator,
    ) : CustomizationCommand

    /** Removes the Modulator and every Reaction and Macro link that used it. */
    data class RemoveModulator(
        val id: ModulatorId,
    ) : CustomizationCommand

    /** Play or stop a Modulator started by hand. A live action, not an edit: it is not in the history. */
    data class PlayModulator(
        val id: ModulatorId,
        val playing: Boolean,
    ) : CustomizationCommand

    /** Sets the normaliser's trim for [signal]. */
    data class TrimSignal(
        val signal: SoundSignal,
        val trim: SignalTrim,
    ) : CustomizationCommand

    /** Replaces the Automations; what makes no sense in them is dropped or brought into range. */
    data class SetAutomations(
        val automations: Automations,
    ) : CustomizationCommand

    /**
     * The Visualizer styles, the Styles of the Visuals feature in their order, which `:app` hands in
     * because Customization cannot depend on Visuals (ADR-0005). Not an edit: kept out of the history.
     */
    data class UseVisualizerStyles(
        val styles: List<SceneId>,
    ) : CustomizationCommand

    /** Back to the current Style's own defaults. Undoable. */
    data object ResetToStyle : CustomizationCommand

    data object Undo : CustomizationCommand

    data object Redo : CustomizationCommand

    /** Saves the current Customization as a preset named [name], keeping only the ticked [parts]. */
    data class SavePreset(
        val name: String,
        val parts: Set<PresetPart>,
    ) : CustomizationCommand

    /** Loads the saved preset [id] and applies the parts it kept. */
    data class LoadPreset(
        val id: PresetId,
    ) : CustomizationCommand

    /** Applies [preset]'s kept parts to the current Customization. */
    data class ApplyPreset(
        val preset: Preset,
    ) : CustomizationCommand
}

/** One-off facts Customization owns. */
sealed interface CustomizationFact {
    /** The Visual now shows [style]. */
    data class StyleChanged(
        val style: SceneId,
    ) : CustomizationFact
}
