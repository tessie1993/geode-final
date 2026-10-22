package dev.geode.feature.visuals.core

import dev.geode.engine.types.SceneId
import dev.geode.feature.visuals.api.MilkdropShelf
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsState
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.Reducer
import dev.geode.kernel.ReportFailure

/**
 * The Visuals feature's state machine (#43).
 *
 * Which Style the Visual shows is Customization's (ADR-0002): choosing one only asks Customization,
 * and [VisualsState.current] moves when Customization says so, whoever asked (this tab, a preset, an
 * undo). Each Style it shows goes to the front of Recent.
 *
 * The person's library (Favourites, Recent and folders) is read once when the store starts. Until it
 * has loaded, edits to it are ignored, so a late read can never overwrite them, and every change after
 * that is saved at once. A library that cannot be read stays unloaded, so a failed read is never
 * saved over.
 *
 * The MilkDrop presets and textures (#145) are listed once when the store starts. An import runs one
 * at a time, and only once the folder has been listed; while it runs, another is ignored. Its report
 * stays until the person dismisses it or starts the next import. An import that fails as a whole is
 * reported on the app-wide error channel, and the next one may start.
 */
object VisualsReducer : Reducer<VisualsState, VisualsEvent, VisualsEffect> {
    /** What the store carries out once, when it is built. */
    val start: List<VisualsEffect> =
        listOf(Perform(VisualsWork.FollowCurrentStyle), Perform(VisualsWork.LoadLibrary), Perform(VisualsWork.ListMilkdrop))

    override fun reduce(
        state: VisualsState,
        event: VisualsEvent,
    ): Next<VisualsState, VisualsEffect> =
        when (event) {
            is VisualsEvent.Command -> command(state, event.command)
            is VisualsEvent.CurrentStyle -> shown(state, event.style)
            is VisualsEvent.LibraryLoaded -> loaded(state, event.library)
            is VisualsEvent.MilkdropListed -> listed(state, event)
            is VisualsEvent.MilkdropImported -> imported(state, event)
            is VisualsEvent.WorkFailed -> failed(state, event.failure)
        }

    private fun command(
        state: VisualsState,
        command: VisualsCommand,
    ): Next<VisualsState, VisualsEffect> =
        when (command) {
            is VisualsCommand.Choose -> choose(state, command.style)
            is VisualsCommand.SetFavourite -> edit(state) { LibraryEdits.favourite(it, state, command) }
            is VisualsCommand.CreateFolder -> LibraryEdits.create(state, command)
            is VisualsCommand.RenameFolder -> edit(state) { LibraryEdits.rename(it, command) }
            is VisualsCommand.MoveFolder -> edit(state) { LibraryEdits.move(it, command) }
            is VisualsCommand.DeleteFolder -> edit(state) { LibraryEdits.delete(it, command.id) }
            is VisualsCommand.AddToFolder -> edit(state) { LibraryEdits.add(it, state, command) }
            is VisualsCommand.RemoveFromFolder -> edit(state) { LibraryEdits.remove(it, command) }
            is VisualsCommand.ImportMilkdrop -> import(state, command)
            VisualsCommand.DismissImportReport -> dismiss(state)
        }

    /** Applies [change] to the loaded library and saves the result; nothing happens when it changes nothing. */
    private inline fun edit(
        state: VisualsState,
        change: (StyleLibrary) -> StyleLibrary,
    ): Next<VisualsState, VisualsEffect> {
        val library = state.library ?: return Next(state)
        val changed = change(library)
        return if (changed == library) Next(state) else saving(state, changed)
    }

    private fun choose(
        state: VisualsState,
        style: SceneId,
    ): Next<VisualsState, VisualsEffect> =
        if (style == state.current || !state.draws(style)) {
            Next(state)
        } else {
            Next(state, listOf(Perform(VisualsWork.ChooseStyle(style))))
        }

    private fun shown(
        state: VisualsState,
        style: SceneId,
    ): Next<VisualsState, VisualsEffect> {
        if (style == state.current) return Next(state)
        val library = state.library ?: return Next(state.copy(current = style))
        return saving(state.copy(current = style), library.withRecent(style))
    }

    private fun loaded(
        state: VisualsState,
        library: StyleLibrary,
    ): Next<VisualsState, VisualsEffect> {
        if (state.library != null) return Next(state)
        val current = state.current
        if (current == null || library.recent.firstOrNull() == current) return Next(state.copy(library = library))
        return saving(state, library.withRecent(current))
    }

    private fun import(
        state: VisualsState,
        command: VisualsCommand.ImportMilkdrop,
    ): Next<VisualsState, VisualsEffect> {
        val shelf = state.milkdrop
        val files = command.files.distinct()
        if (shelf == null || shelf.importing || files.isEmpty()) return Next(state)
        return Next(
            state.copy(milkdrop = shelf.copy(importing = true, lastImport = null)),
            listOf(Perform(VisualsWork.ImportMilkdrop(command.kind, files))),
        )
    }

    private fun dismiss(state: VisualsState): Next<VisualsState, VisualsEffect> {
        val shelf = state.milkdrop ?: return Next(state)
        if (shelf.lastImport == null) return Next(state)
        return Next(state.copy(milkdrop = shelf.copy(lastImport = null)))
    }

    private fun listed(
        state: VisualsState,
        event: VisualsEvent.MilkdropListed,
    ): Next<VisualsState, VisualsEffect> =
        if (state.milkdrop != null) Next(state) else Next(state.copy(milkdrop = MilkdropShelf(event.presets, event.textures)))

    private fun imported(
        state: VisualsState,
        event: VisualsEvent.MilkdropImported,
    ): Next<VisualsState, VisualsEffect> =
        Next(state.copy(milkdrop = MilkdropShelf(event.presets, event.textures, importing = false, lastImport = event.report)))

    private fun failed(
        state: VisualsState,
        failure: WorkFailure,
    ): Next<VisualsState, VisualsEffect> {
        val report = listOf(ReportFailure(failure.message))
        val shelf = state.milkdrop
        if (failure != WorkFailure.MILKDROP_NOT_IMPORTED || shelf == null) return Next(state, report)
        return Next(state.copy(milkdrop = shelf.copy(importing = false)), report)
    }

    /** [state] with [library], saved. */
    internal fun saving(
        state: VisualsState,
        library: StyleLibrary,
    ): Next<VisualsState, VisualsEffect> = Next(state.copy(library = library), listOf(Perform(VisualsWork.SaveLibrary(library))))

    private fun StyleLibrary.withRecent(style: SceneId): StyleLibrary =
        copy(recent = (listOf(style) + recent.filterNot { it == style }).take(StyleLibrary.RECENT_LIMIT))
}

/** Whether this version draws [style]. */
internal fun VisualsState.draws(style: SceneId): Boolean = styles.any { it.id == style }
