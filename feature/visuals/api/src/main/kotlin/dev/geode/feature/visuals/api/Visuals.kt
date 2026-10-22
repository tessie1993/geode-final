package dev.geode.feature.visuals.api

import dev.geode.engine.types.SceneId
import dev.geode.kernel.Feature

/**
 * The Visuals feature as everything outside it sees it (ADR-0003, ADR-0005): the place in the App UI
 * where a Style is chosen for the Visual, and full-screen visual mode.
 *
 * It owns only how the person finds Styles: the Style list grouped by family, their Favourites, the
 * Styles they used most recently, and folders they make, which can nest; and the MilkDrop presets and
 * textures, built-in and the ones the person imports through the system picker. Which Style the Visual
 * shows belongs to Customization; choosing one here asks Customization for it, and [VisualsState.current]
 * follows what Customization says. The music belongs to Playback, which this feature never touches,
 * so moving between the Visuals tab, full-screen visual mode and any other place never restarts the
 * music or the Visual.
 */
typealias Visuals = Feature<VisualsState, VisualsCommand, VisualsFact>

/** The families the Visuals tab groups Styles by, in the order it shows them. */
enum class StyleGroup {
    SHADERS,
    LIFE,
    MYCELIUM,
    ACID,
    FLUIDS,
    CURL_FLOW,
    WATER,
    CYMATICS,
    MILKDROP,
}

/** One Style to choose from: its [id], the [name] it is shown by, and the [group] it is listed in. */
data class StyleEntry(
    val id: SceneId,
    val name: String,
    val group: StyleGroup,
)

/** A folder the person made. */
@JvmInline
value class FolderId(
    val value: Long,
) {
    override fun toString(): String = "folder $value"
}

/**
 * A folder of Styles the person made and named. It sits inside [parent], or at the top when that is
 * null, and holds [styles] in the order they were added.
 */
data class Folder(
    val id: FolderId,
    val name: String,
    val parent: FolderId?,
    val styles: List<SceneId>,
)

/**
 * The person's own way of finding Styles, kept between launches: [favourites] (the latest first),
 * [recent] (the Styles the Visual showed lately, the latest first, at most [RECENT_LIMIT]) and their
 * [folders]. It may name a Style this version no longer has; the Visuals tab leaves those out.
 */
data class StyleLibrary(
    val favourites: List<SceneId> = emptyList(),
    val recent: List<SceneId> = emptyList(),
    val folders: List<Folder> = emptyList(),
) {
    /** The folders directly inside [parent], or at the top when it is null, in the order they were made. */
    fun foldersIn(parent: FolderId?): List<Folder> = folders.filter { it.parent == parent }

    fun folder(id: FolderId): Folder? = folders.firstOrNull { it.id == id }

    companion object {
        /** How many Styles [recent] keeps. */
        const val RECENT_LIMIT = 12
    }
}

/**
 * Everything Visuals lets the outside read: the [styles] this version draws, in the order they are
 * listed, the Style the Visual shows now ([current], null until Customization has said), the
 * person's [library], null until it has been read back from the phone, and the MilkDrop presets and
 * textures, built-in and imported ([milkdrop], null until the MilkDrop folder has been listed).
 */
data class VisualsState(
    val styles: List<StyleEntry> = emptyList(),
    val current: SceneId? = null,
    val library: StyleLibrary? = null,
    val milkdrop: MilkdropShelf? = null,
)

/**
 * What the outside may ask of Visuals. A Command that names a Style this version does not draw, or
 * a folder that does not exist, does nothing; so does any edit of the library before it has loaded.
 */
sealed interface VisualsCommand {
    /** Show [style] in the Visual. Customization switches to it; the engine blends to it. */
    data class Choose(
        val style: SceneId,
    ) : VisualsCommand

    /** Adds [style] to the Favourites, or takes it out. */
    data class SetFavourite(
        val style: SceneId,
        val favourite: Boolean,
    ) : VisualsCommand

    /** Makes a folder named [name] inside [parent] (the top when null). A blank name makes nothing. */
    data class CreateFolder(
        val name: String,
        val parent: FolderId? = null,
    ) : VisualsCommand

    /** Renames the folder; a blank name changes nothing. */
    data class RenameFolder(
        val id: FolderId,
        val name: String,
    ) : VisualsCommand

    /** Moves the folder into [parent] (the top when null); never into itself or a folder inside it. */
    data class MoveFolder(
        val id: FolderId,
        val parent: FolderId?,
    ) : VisualsCommand

    /** Deletes the folder and every folder inside it. The Styles themselves stay. */
    data class DeleteFolder(
        val id: FolderId,
    ) : VisualsCommand

    /** Adds [style] to the end of the folder, unless it is already there. */
    data class AddToFolder(
        val folder: FolderId,
        val style: SceneId,
    ) : VisualsCommand

    data class RemoveFromFolder(
        val folder: FolderId,
        val style: SceneId,
    ) : VisualsCommand

    /**
     * Imports [files] (content uris from the system picker) as MilkDrop presets or textures, each on
     * its own: a broken one is reported in [MilkdropShelf.lastImport] and the rest still come in. Does
     * nothing while another import runs or before the MilkDrop folder has been listed.
     */
    data class ImportMilkdrop(
        val kind: MilkdropKind,
        val files: List<String>,
    ) : VisualsCommand

    /** Forgets [MilkdropShelf.lastImport], once the person has read it. */
    data object DismissImportReport : VisualsCommand
}

/** One-off facts Visuals announces. */
sealed interface VisualsFact {
    /** The folder [id] was made, so the screen can open it. */
    data class FolderCreated(
        val id: FolderId,
    ) : VisualsFact
}
