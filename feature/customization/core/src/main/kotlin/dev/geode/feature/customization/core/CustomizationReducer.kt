package dev.geode.feature.customization.core

import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.CustomizationFact
import dev.geode.feature.customization.api.CustomizationState
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
 * - Undo and redo are unlimited for the session; reset to Style is an edit like any other.
 * - Playing or stopping a hand-started Modulator is live: it reaches the engine but not the history.
 * - Presets keep the ticked parts; a preset applied on another Style brings only the shared parts.
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
            is CustomizationEvent.Command -> command(state, event.command)
            is CustomizationEvent.PresetLoaded -> edit(state, PresetRules.apply(state.current, event.preset, styles))
            is CustomizationEvent.WorkFailed -> Next(state, listOf(ReportFailure(event.failure.message)))
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
            CustomizationCommand.ResetToStyle -> edit(state, Defaults.customization(styles.manifestOf(current.style)))
            CustomizationCommand.Undo -> undo(state)
            CustomizationCommand.Redo -> redo(state)
            is CustomizationCommand.SavePreset ->
                if (command.name.isBlank()) {
                    Next(state)
                } else {
                    Next(state, listOf(Perform(CustomizationWork.SavePreset(PresetRules.capture(command.name, current, command.parts)))))
                }
            is CustomizationCommand.LoadPreset -> Next(state, listOf(Perform(CustomizationWork.LoadPreset(command.id))))
            is CustomizationCommand.ApplyPreset -> edit(state, PresetRules.apply(current, command.preset, styles))
        }
    }

    private fun chooseStyle(
        state: CustomizationState,
        command: CustomizationCommand.ChooseStyle,
    ): Next<CustomizationState, CustomizationEffect> {
        val target = styles[command.style] ?: return Next(state, listOf(ReportFailure("no Style named ${command.style}")))
        if (command.style == state.current.style) return Next(state)
        return edit(state, Edits.switchStyle(state.current, styles.manifestOf(state.current.style), target))
    }

    /** One undo step to [next], unless it changes nothing. */
    private fun edit(
        state: CustomizationState,
        next: Customization,
    ): Next<CustomizationState, CustomizationEffect> =
        if (next == state.current) {
            Next(state)
        } else {
            Next(CustomizationState(next, state.undo + state.current, emptyList()), handOver(state.current, next))
        }

    /** A live change: handed to the engine, kept out of the history. */
    private fun live(
        state: CustomizationState,
        next: Customization,
    ): Next<CustomizationState, CustomizationEffect> =
        if (next == state.current) Next(state) else Next(state.copy(current = next), handOver(state.current, next))

    private fun undo(state: CustomizationState): Next<CustomizationState, CustomizationEffect> {
        val previous = state.undo.lastOrNull() ?: return Next(state)
        return Next(CustomizationState(previous, state.undo.dropLast(1), state.redo + state.current), handOver(state.current, previous))
    }

    private fun redo(state: CustomizationState): Next<CustomizationState, CustomizationEffect> {
        val following = state.redo.lastOrNull() ?: return Next(state)
        return Next(CustomizationState(following, state.undo + state.current, state.redo.dropLast(1)), handOver(state.current, following))
    }

    private fun handOver(
        from: Customization,
        to: Customization,
    ): List<CustomizationEffect> {
        val publish = Perform(CustomizationWork.Publish(to))
        return if (from.style == to.style) listOf(publish) else listOf(publish, Emit(CustomizationFact.StyleChanged(to.style)))
    }
}
