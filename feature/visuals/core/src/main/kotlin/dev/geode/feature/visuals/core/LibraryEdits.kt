package dev.geode.feature.visuals.core

import dev.geode.feature.visuals.api.Folder
import dev.geode.feature.visuals.api.FolderId
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsFact
import dev.geode.feature.visuals.api.VisualsState
import dev.geode.kernel.Emit
import dev.geode.kernel.Next

/**
 * The person's edits to their library, as pure functions of the library. Each returns the library
 * unchanged when the edit names something that is not there or changes nothing; [VisualsReducer]
 * saves only a library that changed.
 */
internal object LibraryEdits {
    /** The longest folder name kept; anything longer is cut. */
    const val FOLDER_NAME_LIMIT = 60

    private val spaces = Regex("\\s+")

    fun favourite(
        library: StyleLibrary,
        state: VisualsState,
        command: VisualsCommand.SetFavourite,
    ): StyleLibrary {
        val style = command.style
        val favourites = library.favourites
        return when {
            command.favourite && (style in favourites || !state.draws(style)) -> library
            command.favourite -> library.copy(favourites = listOf(style) + favourites)
            else -> library.copy(favourites = favourites - style)
        }
    }

    /** Makes the folder, numbered one after the highest in use, and announces it. */
    fun create(
        state: VisualsState,
        command: VisualsCommand.CreateFolder,
    ): Next<VisualsState, VisualsEffect> {
        val library = state.library ?: return Next(state)
        val name = clean(command.name) ?: return Next(state)
        val parent = command.parent
        if (parent != null && library.folder(parent) == null) return Next(state)
        val id = FolderId((library.folders.maxOfOrNull { it.id.value } ?: 0L) + 1)
        val made = VisualsReducer.saving(state, library.copy(folders = library.folders + Folder(id, name, parent, emptyList())))
        return made.copy(effects = made.effects + Emit(VisualsFact.FolderCreated(id)))
    }

    fun rename(
        library: StyleLibrary,
        command: VisualsCommand.RenameFolder,
    ): StyleLibrary {
        val name = clean(command.name) ?: return library
        return library.change(command.id) { it.copy(name = name) }
    }

    fun move(
        library: StyleLibrary,
        command: VisualsCommand.MoveFolder,
    ): StyleLibrary {
        val parent = command.parent
        if (parent != null && (library.folder(parent) == null || parent in library.withInside(command.id))) return library
        return library.change(command.id) { it.copy(parent = parent) }
    }

    fun delete(
        library: StyleLibrary,
        id: FolderId,
    ): StyleLibrary {
        val gone = library.withInside(id)
        return library.copy(folders = library.folders.filterNot { it.id in gone })
    }

    fun add(
        library: StyleLibrary,
        state: VisualsState,
        command: VisualsCommand.AddToFolder,
    ): StyleLibrary {
        if (!state.draws(command.style)) return library
        return library.change(command.folder) { if (command.style in it.styles) it else it.copy(styles = it.styles + command.style) }
    }

    fun remove(
        library: StyleLibrary,
        command: VisualsCommand.RemoveFromFolder,
    ): StyleLibrary = library.change(command.folder) { it.copy(styles = it.styles - command.style) }

    /** [name] without stray spaces and cut to [FOLDER_NAME_LIMIT], or null when nothing is left. */
    private fun clean(name: String): String? =
        name
            .trim()
            .replace(spaces, " ")
            .take(FOLDER_NAME_LIMIT)
            .ifEmpty { null }

    /** The library with folder [id] changed by [change]; unchanged when there is no such folder. */
    private inline fun StyleLibrary.change(
        id: FolderId,
        change: (Folder) -> Folder,
    ): StyleLibrary = copy(folders = folders.map { if (it.id == id) change(it) else it })

    /** [id] and every folder inside it, however deep; empty when there is no such folder. */
    private fun StyleLibrary.withInside(id: FolderId): Set<FolderId> {
        if (folder(id) == null) return emptySet()
        val found = mutableSetOf(id)
        var added = true
        while (added) {
            added = folders.filter { it.parent in found && it.id !in found }.map { found.add(it.id) }.isNotEmpty()
        }
        return found
    }
}
