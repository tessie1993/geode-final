package dev.geode.feature.visuals.api

import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.kernel.Feature

/**
 * The Visuals feature as everything outside it sees it (ADR-0003, ADR-0005): the place in the App UI
 * where a Style is chosen for the Visual, and full-screen visual mode.
 *
 * It owns only how the person finds Styles: the Style list grouped by family, their Favourites, the
 * Styles they used most recently, and folders they make, which can nest. Which Style the Visual
 * shows belongs to Customization; choosing one here asks Customization for it, and [VisualsState.current]
 * follows what Customization says. It also keeps the Presets browser (#144): the built-in and saved
 * presets by folder, applied through Customization, and shared as links or files. The music belongs to Playback, which this feature never touches,
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
 * person's [library], null until it has been read back from the phone, and the [presets], null until
 * they have been listed.
 */
data class VisualsState(
    val styles: List<StyleEntry> = emptyList(),
    val current: SceneId? = null,
    val library: StyleLibrary? = null,
    val presets: PresetShelf? = null,
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

    /** Lists the presets again, to pick up ones saved elsewhere (Customize, or the preset folder). */
    data object ReloadPresets : VisualsCommand

    /** Applies the saved or built-in preset [id] to the Visual, through Customization. */
    data class ApplyPreset(
        val id: PresetId,
    ) : VisualsCommand

    /** Renames the saved preset; a blank name, or the name of another saved preset, changes nothing. */
    data class RenamePreset(
        val id: PresetId,
        val name: String,
    ) : VisualsCommand

    /** Moves the saved preset into [folder], which must exist. */
    data class MovePreset(
        val id: PresetId,
        val folder: PresetFolderPath,
    ) : VisualsCommand

    /** Deletes the saved preset and its file, for good. Built-in presets cannot be deleted. */
    data class DeletePreset(
        val id: PresetId,
    ) : VisualsCommand

    /** Makes a folder of presets named [name] inside [parent]. A blank name makes nothing. */
    data class CreatePresetFolder(
        val name: String,
        val parent: PresetFolderPath = PresetFolderPath.TOP,
    ) : VisualsCommand

    /** Renames the folder of presets; what is in it goes with it. */
    data class RenamePresetFolder(
        val folder: PresetFolderPath,
        val name: String,
    ) : VisualsCommand

    /** Deletes the folder of presets when it is empty; a folder that holds anything stays. */
    data class DeletePresetFolder(
        val folder: PresetFolderPath,
    ) : VisualsCommand

    /** Makes the saved preset ready to share, as a link or as its file; answered with [VisualsFact.PresetShareReady]. */
    data class SharePreset(
        val id: PresetId,
    ) : VisualsCommand

    /** Imports the preset link found in [text] (pasted, or from a share); answered with an import fact. */
    data class ImportPresetLink(
        val text: String,
    ) : VisualsCommand

    /** Imports the preset file at [uri] (a document the person picked); answered with an import fact. */
    data class ImportPresetFile(
        val uri: String,
    ) : VisualsCommand
}

/** One-off facts Visuals announces. */
sealed interface VisualsFact {
    /** The folder [id] was made, so the screen can open it. */
    data class FolderCreated(
        val id: FolderId,
    ) : VisualsFact

    /** The preset is ready to hand to the share sheet. */
    data class PresetShareReady(
        val share: PresetShare,
    ) : VisualsFact

    /** A shared preset was saved as [id] (renamed when its name was taken). */
    data class PresetImported(
        val id: PresetId,
    ) : VisualsFact

    /** A shared preset could not be imported, for [problem]. */
    data class PresetNotImported(
        val problem: ImportProblem,
    ) : VisualsFact
}
