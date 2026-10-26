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
 * - [layer]: Layer 2, a second Style drawn over this one; it carries over to the next Style.
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
    val layer: Layer = Layer.OFF,
)

/**
 * Customization's state: the [current] Customization and the session's unlimited undo and redo
 * history, the newest step last. The history survives Android killing the app (ADR-0004).
 *
 * Style switching's own bookkeeping sits beside it, outside the history: [visualizerStyles], the
 * Visualizer styles `:app` hands in ([CustomizationCommand.UseVisualizerStyles]), and [styleSwitches],
 * how many times it has switched, which says where the app's picks' shuffle has got to. [styleHeld] is
 * the person's Hold ("keep this Style") and [editing] says Customize is open: either holds switching
 * ([switchingHeld]), and its spacing starts again once both let go (#282). [styleMarks] and
 * [favouriteStyles] come from the person's Style library, which `:app` hands in
 * ([CustomizationCommand.UseStyleLibrary]); a Style with no mark is Normal (#308).
 *
 * The Styles seen this session sit outside the history too (#282): [stylesBack] holds each Style left,
 * with the Customization it had then, the latest last, and [stylesForward] the ones gone back from, so
 * back and forward walk through them like a browser's.
 *
 * A/B compare's two slots ([compare]) sit outside the history too: what the person kept to switch
 * between while they decide.
 *
 * While a control is being dragged ([CustomizationCommand.Preview]), [previewFrom] is the Customization
 * from before the drag: the Visual follows the finger, and the whole drag becomes one undo step back
 * to it when it ends. Null when nothing is being dragged.
 */
data class CustomizationState(
    val current: Customization,
    val undo: List<Customization> = emptyList(),
    val redo: List<Customization> = emptyList(),
    val visualizerStyles: List<SceneId> = emptyList(),
    val styleSwitches: Int = 0,
    val compare: Map<CompareSlot, Customization> = emptyMap(),
    val previewFrom: Customization? = null,
    val styleHeld: Boolean = false,
    val editing: Boolean = false,
    val stylesBack: List<Customization> = emptyList(),
    val stylesForward: List<Customization> = emptyList(),
    val styleMarks: Map<SceneId, StyleMark> = emptyMap(),
    val favouriteStyles: List<SceneId> = emptyList(),
) {
    /** Whether Style switching waits: held by the person, or Customize is open. */
    val switchingHeld: Boolean
        get() = styleHeld || editing
}

/** A/B compare's two slots. */
enum class CompareSlot {
    A,
    B,
}

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

    /**
     * Hold ("keep this Style", #282): while [held], Style switching does not move; once released its
     * spacing starts again. Session state, not an edit: kept out of the history and out of presets.
     */
    data class HoldStyle(
        val held: Boolean,
    ) : CustomizationCommand

    /**
     * Whether Customize is open (#282): while it is, Style switching waits, so a switch never throws away
     * fresh edits, and its spacing starts again when it closes. Not an edit.
     */
    data class SetEditing(
        val editing: Boolean,
    ) : CustomizationCommand

    /**
     * Back to the Style seen before this one, with the Customization it had then, the current
     * Automations kept (#282). Live like Style switching's own moves: kept out of the history.
     */
    data object StyleBack : CustomizationCommand

    /** Forward again to the Style [StyleBack] left. Live, kept out of the history. */
    data object StyleForward : CustomizationCommand

    /**
     * The person's [marks] and [favourites] from the Style library (#308), which `:app` hands in because
     * Customization cannot depend on Visuals (ADR-0005). Style switching draws by them. Not an edit.
     */
    data class UseStyleLibrary(
        val marks: Map<SceneId, StyleMark>,
        val favourites: List<SceneId>,
    ) : CustomizationCommand

    /**
     * Lays [style] over the current Style as Layer 2, or turns Layer 2 off when null. Ignored for a
     * Style that cannot be Layer 2: one this version does not know, MilkDrop, or the current Style.
     */
    data class ChooseLayerStyle(
        val style: SceneId?,
    ) : CustomizationCommand

    data class SetLayerBlend(
        val blend: LayerBlend,
    ) : CustomizationCommand

    data class SetLayerInfluence(
        val influence: LayerInfluence,
    ) : CustomizationCommand

    /** Sets Layer 2's loop, or stops it when null. Its depth is brought into 0..1, and a loop with no target is no loop. */
    data class SetLayerLoop(
        val loop: LayerLoop?,
    ) : CustomizationCommand

    /** Back to the current Style's own defaults. Undoable. */
    data object ResetToStyle : CustomizationCommand

    /**
     * Puts one [target] back to the current Style's own starting value: a Shared core property or an
     * Extra (a Macro rests at its middle). Undoable; nothing happens for a Modulator's field.
     */
    data class ResetValue(
        val target: Target,
    ) : CustomizationCommand

    /**
     * [edit] shown while a control is dragged (#285): the Visual follows the finger, but the history
     * waits. The drag ends with [edit] itself, sent as usual, and the whole drag is one undo step.
     * Only value edits can be previewed ([SetShared], [SetExtra], [SetMacro], [TrimSignal],
     * [UpdateReaction] and [UpdateModulator]); anything else is ignored.
     */
    data class Preview(
        val edit: CustomizationCommand,
    ) : CustomizationCommand

    /** Keeps the current Customization in A/B compare's [slot], replacing what it kept. Not an edit. */
    data class KeepForCompare(
        val slot: CompareSlot,
    ) : CustomizationCommand

    /**
     * Switches to what A/B compare's [slot] keeps, the current Automations kept as they are. An edit
     * like any other; nothing happens while the slot is empty.
     */
    data class Compare(
        val slot: CompareSlot,
    ) : CustomizationCommand

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
