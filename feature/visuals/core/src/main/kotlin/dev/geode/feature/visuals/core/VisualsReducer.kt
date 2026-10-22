package dev.geode.feature.visuals.core

import dev.geode.engine.types.SceneId
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsFact
import dev.geode.feature.visuals.api.VisualsState
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.Reducer
import dev.geode.kernel.ReportFailure

/**
 * The Visuals feature's state machine (#43), with the Presets browser (#144) in [PresetEdits].
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
 * The presets are listed when the store starts and again whenever the browser asks, so presets saved
 * from Customize or put in the preset folder by hand show up.
 */
object VisualsReducer : Reducer<VisualsState, VisualsEvent, VisualsEffect> {
    /** What the store carries out once, when it is built. */
    val start: List<VisualsEffect> =
        listOf(Perform(VisualsWork.FollowCurrentStyle), Perform(VisualsWork.LoadLibrary), Perform(VisualsWork.LoadPresets))

    override fun reduce(
        state: VisualsState,
        event: VisualsEvent,
    ): Next<VisualsState, VisualsEffect> =
        when (event) {
            is VisualsEvent.Command -> command(state, event.command)
            is VisualsEvent.CurrentStyle -> shown(state, event.style)
            is VisualsEvent.LibraryLoaded -> loaded(state, event.library)
            is VisualsEvent.PresetsLoaded -> Next(state.copy(presets = PresetEdits.tidy(event.shelf)))
            is VisualsEvent.PresetEncoded -> PresetEdits.encoded(state, event)
            is VisualsEvent.PresetImported -> PresetEdits.imported(state, event.preset)
            is VisualsEvent.ImportRefused -> Next(state, listOf(Emit(VisualsFact.PresetNotImported(event.problem))))
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
            VisualsCommand.ReloadPresets -> Next(state, listOf(Perform(VisualsWork.LoadPresets)))
            is VisualsCommand.ApplyPreset -> PresetEdits.apply(state, command.id)
            is VisualsCommand.RenamePreset -> PresetEdits.rename(state, command)
            is VisualsCommand.MovePreset -> PresetEdits.move(state, command)
            is VisualsCommand.DeletePreset -> PresetEdits.delete(state, command.id)
            is VisualsCommand.CreatePresetFolder -> PresetEdits.createFolder(state, command)
            is VisualsCommand.RenamePresetFolder -> PresetEdits.renameFolder(state, command)
            is VisualsCommand.DeletePresetFolder -> PresetEdits.deleteFolder(state, command.folder)
            is VisualsCommand.SharePreset -> PresetEdits.share(state, command.id)
            is VisualsCommand.ImportPresetLink -> PresetEdits.importLink(state, command.text)
            is VisualsCommand.ImportPresetFile -> Next(state, listOf(Perform(VisualsWork.ImportPresetFile(command.uri))))
        }

    /** Reports [failure]; after one that may leave the presets on the phone different, lists them again. */
    private fun failed(
        state: VisualsState,
        failure: WorkFailure,
    ): Next<VisualsState, VisualsEffect> {
        val report = ReportFailure(failure.message)
        return Next(state, if (failure.resyncs) listOf(report, Perform(VisualsWork.LoadPresets)) else listOf(report))
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
