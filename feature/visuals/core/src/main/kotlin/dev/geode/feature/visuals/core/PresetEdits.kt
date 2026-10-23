package dev.geode.feature.visuals.core

import dev.geode.engine.types.PresetId
import dev.geode.feature.visuals.api.ImportProblem
import dev.geode.feature.visuals.api.PresetFolderPath
import dev.geode.feature.visuals.api.PresetShare
import dev.geode.feature.visuals.api.PresetShelf
import dev.geode.feature.visuals.api.SavedPreset
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsFact
import dev.geode.feature.visuals.api.VisualsState
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform

/**
 * The Presets browser's part of the state machine (#144). The presets live in files Customization
 * reads (#142), so every change is made to the shelf at once, for the screen, and carried out on the
 * phone by the handlers; when that fails the presets are listed again ([WorkFailure.resyncs]).
 *
 * Until the presets have been listed, every edit is ignored. A Command that names a preset or folder
 * that is not there, or changes nothing, does nothing. Built-in presets can only be applied.
 */
internal object PresetEdits {
    /** The listed presets, tidied: saved presets by name, and every folder that holds one listed too. */
    fun tidy(shelf: PresetShelf): PresetShelf {
        val named = (shelf.folders + shelf.saved.map { it.folder }).flatMap { folder -> generateSequence(folder) { it.parent }.toList() }
        return shelf.copy(
            saved = shelf.saved.distinctBy { it.id }.sortedBy { it.id.value.lowercase() },
            folders = named.filterNot { it.isTop }.distinct().sortedBy { it.value.lowercase() },
        )
    }

    /** A saved preset is ready to share: as a link when it fits, otherwise as its file. */
    fun encoded(
        state: VisualsState,
        event: VisualsEvent.PresetEncoded,
    ): Next<VisualsState, VisualsEffect> {
        val share = if (PresetLinks.fits(event.link)) PresetShare.Link(event.id, event.link) else PresetShare.File(event.id)
        return Next(state, listOf(Emit(VisualsFact.PresetShareReady(share))))
    }

    /** A shared preset was saved: it joins the shelf, replacing one of the same name, and is announced. */
    fun imported(
        state: VisualsState,
        preset: SavedPreset,
    ): Next<VisualsState, VisualsEffect> {
        val shelf = state.presets
        val next = if (shelf == null) state else state.copy(presets = tidy(shelf.copy(saved = listOf(preset) + shelf.saved)))
        return Next(next, listOf(Emit(VisualsFact.PresetImported(preset.id))))
    }

    /** Imports the link in [text], or says there is none. */
    fun importLink(
        state: VisualsState,
        text: String,
    ): Next<VisualsState, VisualsEffect> {
        val link = PresetLinks.findIn(text) ?: return Next(state, listOf(Emit(VisualsFact.PresetNotImported(ImportProblem.NO_LINK))))
        return Next(state, listOf(Perform(VisualsWork.ImportPresetLink(link))))
    }

    fun share(
        state: VisualsState,
        id: PresetId,
    ): Next<VisualsState, VisualsEffect> =
        onShelf(state) { shelf ->
            if (shelf.saved(id) == null) Next(state) else Next(state, listOf(Perform(VisualsWork.EncodePreset(id))))
        }

    fun apply(
        state: VisualsState,
        id: PresetId,
    ): Next<VisualsState, VisualsEffect> = onShelf(state) { shelf -> apply(state, shelf, id) }

    fun rename(
        state: VisualsState,
        command: VisualsCommand.RenamePreset,
    ): Next<VisualsState, VisualsEffect> = onShelf(state) { rename(state, it, command) }

    fun move(
        state: VisualsState,
        command: VisualsCommand.MovePreset,
    ): Next<VisualsState, VisualsEffect> = onShelf(state) { move(state, it, command) }

    fun delete(
        state: VisualsState,
        id: PresetId,
    ): Next<VisualsState, VisualsEffect> = onShelf(state) { delete(state, it, id) }

    fun createFolder(
        state: VisualsState,
        command: VisualsCommand.CreatePresetFolder,
    ): Next<VisualsState, VisualsEffect> = onShelf(state) { createFolder(state, it, command) }

    fun renameFolder(
        state: VisualsState,
        command: VisualsCommand.RenamePresetFolder,
    ): Next<VisualsState, VisualsEffect> = onShelf(state) { renameFolder(state, it, command) }

    fun deleteFolder(
        state: VisualsState,
        folder: PresetFolderPath,
    ): Next<VisualsState, VisualsEffect> = onShelf(state) { deleteFolder(state, it, folder) }

    /** [edit] of the listed presets; nothing happens before they have been listed. */
    private inline fun onShelf(
        state: VisualsState,
        edit: (PresetShelf) -> Next<VisualsState, VisualsEffect>,
    ): Next<VisualsState, VisualsEffect> {
        val shelf = state.presets ?: return Next(state)
        return edit(shelf)
    }

    private fun apply(
        state: VisualsState,
        shelf: PresetShelf,
        id: PresetId,
    ): Next<VisualsState, VisualsEffect> =
        when {
            shelf.saved(id) != null -> Next(state, listOf(Perform(VisualsWork.ApplySavedPreset(id))))
            shelf.builtIn(id) != null -> Next(state, listOf(Perform(VisualsWork.ApplyBuiltInPreset(id))))
            else -> Next(state)
        }

    private fun rename(
        state: VisualsState,
        shelf: PresetShelf,
        command: VisualsCommand.RenamePreset,
    ): Next<VisualsState, VisualsEffect> {
        val preset = shelf.saved(command.id) ?: return Next(state)
        val name = PresetNames.preset(command.name) ?: return Next(state)
        val renamed = PresetId(name)
        if (shelf.saved(renamed) != null) return Next(state)
        val saved = shelf.saved.map { if (it == preset) it.copy(id = renamed) else it }
        return changed(state, shelf.copy(saved = saved), VisualsWork.RenamePreset(command.id, name))
    }

    private fun move(
        state: VisualsState,
        shelf: PresetShelf,
        command: VisualsCommand.MovePreset,
    ): Next<VisualsState, VisualsEffect> {
        val preset = shelf.saved(command.id) ?: return Next(state)
        val folder = command.folder
        if (preset.folder == folder || !shelf.has(folder)) return Next(state)
        val saved = shelf.saved.map { if (it == preset) it.copy(folder = folder) else it }
        return changed(state, shelf.copy(saved = saved), VisualsWork.MovePreset(command.id, folder))
    }

    private fun delete(
        state: VisualsState,
        shelf: PresetShelf,
        id: PresetId,
    ): Next<VisualsState, VisualsEffect> {
        val preset = shelf.saved(id) ?: return Next(state)
        return changed(state, shelf.copy(saved = shelf.saved - preset), VisualsWork.DeletePreset(id))
    }

    private fun createFolder(
        state: VisualsState,
        shelf: PresetShelf,
        command: VisualsCommand.CreatePresetFolder,
    ): Next<VisualsState, VisualsEffect> {
        val name = PresetNames.folder(command.name) ?: return Next(state)
        if (!shelf.has(command.parent)) return Next(state)
        val folder = command.parent.child(name)
        if (shelf.has(folder)) return Next(state)
        return changed(state, shelf.copy(folders = shelf.folders + folder), VisualsWork.CreatePresetFolder(folder))
    }

    private fun renameFolder(
        state: VisualsState,
        shelf: PresetShelf,
        command: VisualsCommand.RenamePresetFolder,
    ): Next<VisualsState, VisualsEffect> {
        val from = command.folder
        if (from !in shelf.folders) return Next(state)
        // A listed folder is never the top, so it always has a parent.
        val parent = from.parent ?: return Next(state)
        val name = PresetNames.folder(command.name) ?: return Next(state)
        val to = parent.child(name)
        if (shelf.has(to)) return Next(state)
        val moved = { folder: PresetFolderPath ->
            if (folder.within(from)) PresetFolderPath(to.value + folder.value.removePrefix(from.value)) else folder
        }
        val renamed = shelf.copy(saved = shelf.saved.map { it.copy(folder = moved(it.folder)) }, folders = shelf.folders.map(moved))
        return changed(state, renamed, VisualsWork.RenamePresetFolder(from, to))
    }

    private fun deleteFolder(
        state: VisualsState,
        shelf: PresetShelf,
        folder: PresetFolderPath,
    ): Next<VisualsState, VisualsEffect> {
        if (folder !in shelf.folders) return Next(state)
        val holds = shelf.saved.any { it.folder.within(folder) } || shelf.folders.any { it != folder && it.within(folder) }
        if (holds) return Next(state)
        return changed(state, shelf.copy(folders = shelf.folders - folder), VisualsWork.DeletePresetFolder(folder))
    }

    /** [state] with the [shelf] tidied, and [work] to make the same change on the phone. */
    private fun changed(
        state: VisualsState,
        shelf: PresetShelf,
        work: VisualsWork,
    ): Next<VisualsState, VisualsEffect> = Next(state.copy(presets = tidy(shelf)), listOf(Perform(work)))

    /** Whether [folder] is the top or one of the listed folders. */
    private fun PresetShelf.has(folder: PresetFolderPath): Boolean = folder.isTop || folder in folders
}
