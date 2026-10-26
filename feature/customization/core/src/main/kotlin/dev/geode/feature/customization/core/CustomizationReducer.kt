package dev.geode.feature.customization.core

import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.CustomizationFact
import dev.geode.feature.customization.api.CustomizationState
import dev.geode.feature.customization.api.Preset
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.Reducer
import dev.geode.kernel.ReportFailure

/**
 * Customization's state machine (ADR-0003, decisions on #11):
 *
 * - Every edit is a pure function of the current Customization ([Edits]). An edit that changes
 *   something becomes one undo step, clears the redo history and hands the new Customization to the
 *   engine; an edit that changes nothing leaves everything as it is.
 * - Undo and redo are unlimited for the session; reset to Style, and resetting one value, are edits
 *   like any other.
 * - A dragged control is previewed ([CustomizationCommand.Preview]): each step reaches the engine but
 *   not the history, and the drag ends with the edit itself, one undo step back to before the drag.
 *   Anything else that comes first settles the drag as that one step.
 * - Playing or stopping a hand-started Modulator is live: it reaches the engine but not the history.
 * - Presets keep the ticked parts; a preset applied on another Style brings only the shared parts. A
 *   preset with parts only a newer Geode reads applies the rest and says so ([NEEDS_NEWER_GEODE]).
 * - Setting the Automations is the person's edit; what the Automations do by themselves, Style
 *   switching's moves ([CustomizationEvent.StyleDue]), is live and stays out of the history. They are
 *   kept across a change of Style and a reset to Style, and never saved in a preset.
 * - The Visualizer styles `:app` hands in are kept beside the history, not in it, as are A/B
 *   compare's slots; switching to a slot is an edit.
 * - A change of Style is announced as [CustomizationFact.StyleChanged].
 *
 * [styles] are the manifests of the Styles this version knows.
 */
class CustomizationReducer(
    private val styles: StyleCatalogue,
) : Reducer<CustomizationState, CustomizationEvent, CustomizationEffect> {
    override fun reduce(
        state: CustomizationState,
        event: CustomizationEvent,
    ): Next<CustomizationState, CustomizationEffect> =
        when (event) {
            is CustomizationEvent.Command -> command(if (endsPreview(event.command)) settled(state) else state, event.command)
            is CustomizationEvent.PresetLoaded -> applyPreset(settled(state), event.preset)
            is CustomizationEvent.WorkFailed -> Next(state, listOf(ReportFailure(event.failure.message)))
            CustomizationEvent.StyleDue -> autoSwitch(settled(state))
        }

    /** Whether [command] settles a drag before it is handled: anything but a preview and the value edits a drag ends with. */
    private fun endsPreview(command: CustomizationCommand): Boolean = command !is CustomizationCommand.Preview && !previewable(command)

    /** Whether a drag can preview [command]: the value edits. */
    private fun previewable(command: CustomizationCommand): Boolean =
        when (command) {
            is CustomizationCommand.SetShared,
            is CustomizationCommand.SetExtra,
            is CustomizationCommand.SetMacro,
            is CustomizationCommand.TrimSignal,
            is CustomizationCommand.UpdateReaction,
            is CustomizationCommand.UpdateModulator,
            -> true
            else -> false
        }

    /** A drag left open becomes one undo step back to before it, so what comes next starts from there. */
    private fun settled(state: CustomizationState): CustomizationState {
        val from = state.previewFrom ?: return state
        if (from == state.current) return state.copy(previewFrom = null)
        return state.copy(undo = state.undo + from, redo = emptyList(), previewFrom = null)
    }

    /** One step of a drag: [edit] reaches the engine, the history waits for the drag to end. */
    private fun preview(
        state: CustomizationState,
        edit: CustomizationCommand,
    ): Next<CustomizationState, CustomizationEffect> {
        if (!previewable(edit)) return Next(state)
        val current = state.current
        val next = command(state, edit).state.current
        if (next == current) return Next(state)
        return Next(state.copy(current = next, previewFrom = state.previewFrom ?: current), handOver(current, next))
    }

    /**
     * Style switching's move to its next Style ([Automation.nextStyle]): live, like a hand-started
     * Modulator, so not in the history. It counts the switch, so the app's picks' shuffle moves on.
     */
    private fun autoSwitch(state: CustomizationState): Next<CustomizationState, CustomizationEffect> {
        val current = state.current
        val switching = current.automations.style ?: return Next(state)
        val builtIns = styles.styles.map { it.id }
        val pick =
            Automation.nextStyle(switching, current.style, state.visualizerStyles, builtIns, state.styleSwitches) ?: return Next(state)
        val switched = Edits.switchStyle(current, styles.manifestOf(current.style), styles.manifestOf(pick.style))
        return Next(state.copy(current = switched, styleSwitches = pick.played), handOver(current, switched))
    }

    private fun command(
        state: CustomizationState,
        command: CustomizationCommand,
    ): Next<CustomizationState, CustomizationEffect> {
        val current = state.current
        return when (command) {
            is CustomizationCommand.ChooseStyle -> chooseStyle(state, command)
            is CustomizationCommand.SetShared -> edit(state, Edits.setShared(current, command.property, command.value))
            is CustomizationCommand.SetExtra -> edit(state, Edits.setExtra(current, command.kind, command.value))
            is CustomizationCommand.SetMacro -> edit(state, Edits.setMacro(current, command.index, command.value))
            is CustomizationCommand.RenameMacro -> edit(state, Edits.renameMacro(current, command.index, command.name))
            is CustomizationCommand.ClearMacro -> edit(state, Edits.clearMacro(current, command.index))
            is CustomizationCommand.LinkMacro -> edit(state, Edits.linkMacro(current, command.index, command.link))
            is CustomizationCommand.UnlinkMacro -> edit(state, Edits.unlinkMacro(current, command.index, command.target))
            is CustomizationCommand.AddReaction -> edit(state, Edits.addReaction(current, command.reaction))
            is CustomizationCommand.UpdateReaction -> edit(state, Edits.updateReaction(current, command.reaction))
            is CustomizationCommand.RemoveReaction -> edit(state, Edits.removeReaction(current, command.id))
            is CustomizationCommand.AddModulator -> edit(state, Edits.addModulator(current, command.modulator))
            is CustomizationCommand.UpdateModulator -> edit(state, Edits.updateModulator(current, command.modulator))
            is CustomizationCommand.RemoveModulator -> edit(state, Edits.removeModulator(current, command.id))
            is CustomizationCommand.PlayModulator -> live(state, Edits.playModulator(current, command.id, command.playing))
            is CustomizationCommand.TrimSignal -> edit(state, Edits.trimSignal(current, command.signal, command.trim))
            is CustomizationCommand.SetAutomations -> edit(state, Edits.setAutomations(current, command.automations))
            is CustomizationCommand.UseVisualizerStyles -> Next(state.copy(visualizerStyles = command.styles))
            CustomizationCommand.ResetToStyle -> edit(state, Edits.resetToStyle(current, styles.manifestOf(current.style)))
            is CustomizationCommand.ResetValue -> edit(state, Edits.resetValue(current, styles.manifestOf(current.style), command.target))
            is CustomizationCommand.Preview -> preview(state, command.edit)
            is CustomizationCommand.KeepForCompare -> Next(state.copy(compare = state.compare + (command.slot to current)))
            is CustomizationCommand.Compare ->
                state.compare[command.slot]?.let { kept -> edit(state, kept.copy(automations = current.automations)) } ?: Next(state)
            CustomizationCommand.Undo -> undo(state)
            CustomizationCommand.Redo -> redo(state)
            is CustomizationCommand.SavePreset ->
                if (command.name.isBlank()) {
                    Next(state)
                } else {
                    Next(state, listOf(Perform(CustomizationWork.SavePreset(PresetRules.capture(command.name, current, command.parts)))))
                }
            is CustomizationCommand.LoadPreset -> Next(state, listOf(Perform(CustomizationWork.LoadPreset(command.id))))
            is CustomizationCommand.ApplyPreset -> applyPreset(state, command.preset)
        }
    }

    /** [preset]'s parts applied as one edit, with the note when some of it needs a newer Geode. */
    private fun applyPreset(
        state: CustomizationState,
        preset: Preset,
    ): Next<CustomizationState, CustomizationEffect> {
        val applied = edit(state, PresetRules.apply(state.current, preset, styles))
        return if (preset.unread == null) applied else applied.copy(effects = applied.effects + ReportFailure(NEEDS_NEWER_GEODE))
    }

    private fun chooseStyle(
        state: CustomizationState,
        command: CustomizationCommand.ChooseStyle,
    ): Next<CustomizationState, CustomizationEffect> {
        val target = styles[command.style] ?: return Next(state, listOf(ReportFailure("no Style named ${command.style}")))
        if (command.style == state.current.style) return Next(state)
        return edit(state, Edits.switchStyle(state.current, styles.manifestOf(state.current.style), target))
    }

    /**
     * One undo step to [next], unless it changes nothing. At the end of a drag the step goes back to
     * before the drag, and the engine, which followed the drag, hears only what it has not seen yet.
     */
    private fun edit(
        state: CustomizationState,
        next: Customization,
    ): Next<CustomizationState, CustomizationEffect> {
        val shown = state.current
        val from = state.previewFrom ?: shown
        val handOver = if (next == shown) emptyList() else handOver(shown, next)
        return if (next == from) {
            Next(state.copy(current = next, previewFrom = null), handOver)
        } else {
            Next(state.copy(current = next, undo = state.undo + from, redo = emptyList(), previewFrom = null), handOver)
        }
    }

    /** A live change: handed to the engine, kept out of the history. */
    private fun live(
        state: CustomizationState,
        next: Customization,
    ): Next<CustomizationState, CustomizationEffect> =
        if (next == state.current) Next(state) else Next(state.copy(current = next), handOver(state.current, next))

    private fun undo(state: CustomizationState): Next<CustomizationState, CustomizationEffect> {
        val previous = state.undo.lastOrNull() ?: return Next(state)
        return Next(
            state.copy(current = previous, undo = state.undo.dropLast(1), redo = state.redo + state.current),
            handOver(state.current, previous),
        )
    }

    private fun redo(state: CustomizationState): Next<CustomizationState, CustomizationEffect> {
        val following = state.redo.lastOrNull() ?: return Next(state)
        return Next(
            state.copy(current = following, undo = state.undo + state.current, redo = state.redo.dropLast(1)),
            handOver(state.current, following),
        )
    }

    private fun handOver(
        from: Customization,
        to: Customization,
    ): List<CustomizationEffect> {
        val publish = Perform(CustomizationWork.Publish(to))
        return if (from.style == to.style) listOf(publish) else listOf(publish, Emit(CustomizationFact.StyleChanged(to.style)))
    }

    companion object {
        /**
         * What the store does once, when it is built from [initial]: convert the presets an older Geode
         * saved, and hand the starting Customization to the engine, which has none until then.
         */
        fun start(initial: CustomizationState): List<CustomizationEffect> =
            listOf(Perform(CustomizationWork.UpgradePresets), Perform(CustomizationWork.Publish(initial.current)))
    }
}
