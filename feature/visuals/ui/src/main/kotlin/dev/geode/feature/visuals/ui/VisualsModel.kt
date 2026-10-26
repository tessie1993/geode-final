package dev.geode.feature.visuals.ui

import androidx.compose.runtime.Immutable
import dev.geode.engine.types.SceneId
import dev.geode.feature.visuals.api.Folder
import dev.geode.feature.visuals.api.FolderId
import dev.geode.feature.visuals.api.StyleEntry
import dev.geode.feature.visuals.api.StyleGroup
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.StyleMark
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsState

/** One Style as the Visuals tab lists it. */
@Immutable
data class StyleItem(
    val id: SceneId,
    val name: String,
    val group: StyleGroup,
    /** The Visual shows it now. */
    val current: Boolean,
    val favourite: Boolean,
    /** How the person marked it for automatic Style switching (#308). */
    val mark: StyleMark = StyleMark.NORMAL,
)

/** One folder as the Visuals tab lists it: [name], and how many Styles and folders it holds ([count]). */
@Immutable
data class FolderItem(
    val id: FolderId,
    val name: String,
    val count: Int,
)

/** One family's Styles. */
@Immutable
data class GroupSection(
    val group: StyleGroup,
    val styles: List<StyleItem>,
)

/**
 * What the Visuals tab shows. At the top level: [favourites], [recent], the top [folders] and every
 * family in [groups]. In an open folder ([path] leads to it): its [folders] and its [folderStyles]. While
 * searching, [results] instead. [allFolders] is every folder, named by its path, for "add to folder".
 */
@Immutable
data class VisualsTabModel(
    val currentName: String?,
    val loading: Boolean,
    val query: String,
    val results: List<StyleItem>?,
    val path: List<FolderItem>,
    val favourites: List<StyleItem>,
    val recent: List<StyleItem>,
    val folders: List<FolderItem>,
    val folderStyles: List<StyleItem>,
    val groups: List<GroupSection>,
    val allFolders: List<FolderItem>,
) {
    /** The folder open now, or null at the top level. */
    val openFolder: FolderId? get() = path.lastOrNull()?.id
}

/** What full-screen visual mode shows over the Visual: the Style's [name], and the Styles either side of it. */
@Immutable
data class FullScreenModel(
    val name: String?,
    val previous: SceneId?,
    val next: SceneId?,
    val controlsShown: Boolean,
)

/** Everything the person can do on the Visuals tab. */
sealed interface VisualsAction {
    data class Choose(
        val style: SceneId,
    ) : VisualsAction

    data class SetFavourite(
        val style: SceneId,
        val favourite: Boolean,
    ) : VisualsAction

    /** Marks [style] Love, Normal or Never for automatic Style switching (#308). */
    data class SetMark(
        val style: SceneId,
        val mark: StyleMark,
    ) : VisualsAction

    /** App UI only: what the search field holds. */
    data class Search(
        val query: String,
    ) : VisualsAction

    /** App UI only: opens [folder], or the top level when null. */
    data class OpenFolder(
        val folder: FolderId?,
    ) : VisualsAction

    /** Makes a folder in the one open now. */
    data class CreateFolder(
        val name: String,
    ) : VisualsAction

    data class RenameFolder(
        val folder: FolderId,
        val name: String,
    ) : VisualsAction

    data class DeleteFolder(
        val folder: FolderId,
    ) : VisualsAction

    data class AddToFolder(
        val folder: FolderId,
        val style: SceneId,
    ) : VisualsAction

    data class RemoveFromFolder(
        val folder: FolderId,
        val style: SceneId,
    ) : VisualsAction

    data object EnterFullScreen : VisualsAction
}

/** Everything the person can do in full-screen visual mode. */
sealed interface FullScreenAction {
    /** A tap on the Visual: shows or hides the controls (App UI only). */
    data object ToggleControls : FullScreenAction

    data class Choose(
        val style: SceneId,
    ) : FullScreenAction

    data object Exit : FullScreenAction

    /** Saves a Snapshot of the Visual (#405). */
    data object Snapshot : FullScreenAction
}

/** The Command an action sends, with [openFolder] the folder open now; null for App-UI-only actions. */
fun VisualsAction.toCommand(openFolder: FolderId?): VisualsCommand? =
    when (this) {
        is VisualsAction.Choose -> VisualsCommand.Choose(style)
        is VisualsAction.SetFavourite -> VisualsCommand.SetFavourite(style, favourite)
        is VisualsAction.SetMark -> VisualsCommand.SetMark(style, mark)
        is VisualsAction.CreateFolder -> VisualsCommand.CreateFolder(name, openFolder)
        is VisualsAction.RenameFolder -> VisualsCommand.RenameFolder(folder, name)
        is VisualsAction.DeleteFolder -> VisualsCommand.DeleteFolder(folder)
        is VisualsAction.AddToFolder -> VisualsCommand.AddToFolder(folder, style)
        is VisualsAction.RemoveFromFolder -> VisualsCommand.RemoveFromFolder(folder, style)
        is VisualsAction.Search, is VisualsAction.OpenFolder, VisualsAction.EnterFullScreen -> null
    }

/** The Visuals tab's model, searching for [query] (blank: not searching) with [openFolder] open. */
fun VisualsState.toTabModel(
    query: String,
    openFolder: FolderId?,
): VisualsTabModel {
    val library = library ?: StyleLibrary()
    val favourites = library.favourites.toSet()
    val byId = styles.associateBy { it.id }

    fun entries(list: List<StyleEntry>) = list.map { it.toItem(current, it.id in favourites, library.markOf(it.id)) }

    fun items(ids: List<SceneId>) = entries(ids.mapNotNull { byId[it] })

    fun folderItem(
        folder: Folder,
        name: String = folder.name,
    ) = FolderItem(folder.id, name, folder.styles.count { it in byId } + library.foldersIn(folder.id).size)

    val open = openFolder?.let(library::folder)
    val path = generateSequence(open) { it.parent?.let(library::folder) }.toList().reversed()
    val search = query.trim()
    val atTop = open == null
    return VisualsTabModel(
        currentName = current?.let { byId[it]?.name },
        loading = this.library == null,
        query = query,
        results = if (search.isEmpty()) null else entries(styles.filter { it.name.contains(search, ignoreCase = true) }),
        path = path.map { folderItem(it) },
        favourites = if (atTop) items(library.favourites) else emptyList(),
        recent = if (atTop) items(library.recent) else emptyList(),
        folders = library.foldersIn(open?.id).map { folderItem(it) },
        folderStyles = open?.let { items(it.styles) }.orEmpty(),
        groups =
            if (atTop) {
                styles
                    .groupBy { it.group }
                    .map { (group, list) -> GroupSection(group, entries(list)) }
            } else {
                emptyList()
            },
        allFolders = library.folders.map { folderItem(it, pathName(library, it)) },
    )
}

/** Full-screen visual mode's model: the Style shown, and the Styles before and after it, round the ends. */
fun VisualsState.toFullScreenModel(controlsShown: Boolean): FullScreenModel {
    val at = styles.indexOfFirst { it.id == current }
    if (at < 0) return FullScreenModel(null, null, null, controlsShown)
    return FullScreenModel(
        name = styles[at].name,
        previous = styles[(at - 1 + styles.size) % styles.size].id,
        next = styles[(at + 1) % styles.size].id,
        controlsShown = controlsShown,
    )
}

private fun StyleEntry.toItem(
    current: SceneId?,
    favourite: Boolean,
    mark: StyleMark,
) = StyleItem(id, name, group, current = id == current, favourite = favourite, mark = mark)

/** "Calm / Deep" for Deep inside Calm. */
private fun pathName(
    library: StyleLibrary,
    folder: Folder,
): String =
    generateSequence(folder) { it.parent?.let(library::folder) }
        .toList()
        .reversed()
        .joinToString(" / ") { it.name }
