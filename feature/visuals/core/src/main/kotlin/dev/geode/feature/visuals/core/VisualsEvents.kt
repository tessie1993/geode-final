package dev.geode.feature.visuals.core

import dev.geode.engine.types.PresetId
import dev.geode.engine.types.SceneId
import dev.geode.feature.visuals.api.ImportProblem
import dev.geode.feature.visuals.api.ImportReport
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.api.MilkdropPackType
import dev.geode.feature.visuals.api.MilkdropPreset
import dev.geode.feature.visuals.api.PresetFolderPath
import dev.geode.feature.visuals.api.PresetShelf
import dev.geode.feature.visuals.api.SavedPreset
import dev.geode.feature.visuals.api.StyleLibrary
import dev.geode.feature.visuals.api.VisualsCommand
import dev.geode.feature.visuals.api.VisualsFact
import dev.geode.kernel.Effect

/** The name Visuals reports its failures under, on the app-wide error channel. */
const val VISUALS_FEATURE = "visuals"

/** Everything the reducer handles: the Commands, and what the handlers report back. */
sealed interface VisualsEvent {
    data class Command(
        val command: VisualsCommand,
    ) : VisualsEvent

    /** Customization says the Visual now shows [style]. */
    data class CurrentStyle(
        val style: SceneId,
    ) : VisualsEvent

    /** The saved library was read back from the phone (empty on a first run). */
    data class LibraryLoaded(
        val library: StyleLibrary,
    ) : VisualsEvent

    /** The presets were listed. */
    data class PresetsLoaded(
        val shelf: PresetShelf,
    ) : VisualsEvent

    /** The saved preset [id] was packed into [link], which may be too long to share as one. */
    data class PresetEncoded(
        val id: PresetId,
        val link: String,
    ) : VisualsEvent

    /** A shared preset was saved as [preset]. */
    data class PresetImported(
        val preset: SavedPreset,
    ) : VisualsEvent

    /** A shared preset could not be read, for [problem]. */
    data class ImportRefused(
        val problem: ImportProblem,
    ) : VisualsEvent

    /** The MilkDrop folder was listed: the [presets], built-in ones among them, and the [textures]. */
    data class MilkdropListed(
        val presets: List<MilkdropPreset>,
        val textures: List<String>,
    ) : VisualsEvent

    /**
     * An import finished as [report] says, and left the MilkDrop folder holding [presets] and [textures].
     * A pack's (#399) files each preset it brought in (by file name) in [folders]: the folder of the
     * Visuals tab it goes in, by name, outermost first ([MilkdropPacks.folders]).
     */
    data class MilkdropImported(
        val report: ImportReport,
        val presets: List<MilkdropPreset>,
        val textures: List<String>,
        val folders: Map<String, List<String>> = emptyMap(),
    ) : VisualsEvent

    /** A file was deleted from the MilkDrop folder, which now holds [presets] and [textures] (#360). */
    data class MilkdropDeleted(
        val presets: List<MilkdropPreset>,
        val textures: List<String>,
    ) : VisualsEvent

    /** A piece of work failed at the handlers' edge; the reducer reports it. */
    data class WorkFailed(
        val failure: WorkFailure,
    ) : VisualsEvent
}

/**
 * Work the handlers could not carry out, named for the log and the app-wide banner. After a failure
 * that [resyncs], the presets on the phone may differ from what the browser shows, so they are
 * listed again.
 */
enum class WorkFailure(
    val message: String,
    val resyncs: Boolean = false,
) {
    LIBRARY_NOT_LOADED("could not read the saved Favourites, Recent and folders"),
    LIBRARY_NOT_SAVED("could not save the Favourites, Recent and folders"),
    STYLE_NOT_CHOSEN("could not switch the Visual to the chosen Style"),
    PRESETS_NOT_LOADED("could not list the presets"),
    PRESET_NOT_APPLIED("could not apply the preset"),
    PRESET_NOT_CHANGED("could not change the saved presets", resyncs = true),
    PRESET_NOT_SHARED("could not read the preset to share it"),
    PRESET_NOT_IMPORTED("could not save the shared preset", resyncs = true),
    MILKDROP_NOT_LISTED("could not list the MilkDrop presets and textures"),
    MILKDROP_NOT_IMPORTED("could not import into the MilkDrop folder"),
    MILKDROP_NOT_DELETED("could not delete from the MilkDrop folder"),
}

/** The handlers' work, carried out by `:feature:visuals:handlers`. */
sealed interface VisualsWork {
    /** Follow the Style Customization shows, for the life of the app; answered with [VisualsEvent.CurrentStyle]. */
    data object FollowCurrentStyle : VisualsWork

    /** Read the saved library; answered with [VisualsEvent.LibraryLoaded]. */
    data object LoadLibrary : VisualsWork

    /** Save [library], replacing what was saved before. */
    data class SaveLibrary(
        val library: StyleLibrary,
    ) : VisualsWork

    /** Ask Customization to show [style]. */
    data class ChooseStyle(
        val style: SceneId,
    ) : VisualsWork

    /** List the built-in and saved presets; answered with [VisualsEvent.PresetsLoaded]. */
    data object LoadPresets : VisualsWork

    /** Ask Customization to load the saved preset [id] and apply it. */
    data class ApplySavedPreset(
        val id: PresetId,
    ) : VisualsWork

    /** Hand the built-in preset [id] to Customization to apply. */
    data class ApplyBuiltInPreset(
        val id: PresetId,
    ) : VisualsWork

    /** Save the preset [id] under [name] instead, in the same folder. */
    data class RenamePreset(
        val id: PresetId,
        val name: String,
    ) : VisualsWork

    data class MovePreset(
        val id: PresetId,
        val folder: PresetFolderPath,
    ) : VisualsWork

    /** Delete the preset [id]'s file. */
    data class DeletePreset(
        val id: PresetId,
    ) : VisualsWork

    data class CreatePresetFolder(
        val folder: PresetFolderPath,
    ) : VisualsWork

    /** Rename the folder [from] to [to], with what is in it. */
    data class RenamePresetFolder(
        val from: PresetFolderPath,
        val to: PresetFolderPath,
    ) : VisualsWork

    /** Delete the empty folder. */
    data class DeletePresetFolder(
        val folder: PresetFolderPath,
    ) : VisualsWork

    /** Pack the saved preset [id] into a link; answered with [VisualsEvent.PresetEncoded]. */
    data class EncodePreset(
        val id: PresetId,
    ) : VisualsWork

    /** Save the preset [link] carries; answered with [VisualsEvent.PresetImported] or [VisualsEvent.ImportRefused]. */
    data class ImportPresetLink(
        val link: String,
    ) : VisualsWork

    /** Save the preset in the document at [uri]; answered as [ImportPresetLink] is. */
    data class ImportPresetFile(
        val uri: String,
    ) : VisualsWork

    /** List the MilkDrop folder beside the built-in presets; answered with [VisualsEvent.MilkdropListed]. */
    data object ListMilkdrop : VisualsWork

    /**
     * Read each of [files] (content uris), check it ([MilkdropFiles.plan]) and write the good ones into
     * the MilkDrop folder; answered with [VisualsEvent.MilkdropImported].
     */
    data class ImportMilkdrop(
        val kind: MilkdropKind,
        val files: List<String>,
    ) : VisualsWork

    /**
     * Walk the pack at [uri] (a folder or a zip, as [type] says), bringing in its presets and textures as
     * [ImportMilkdrop] does and passing over the rest ([MilkdropPacks.sort]), within the pack limits;
     * answered with [VisualsEvent.MilkdropImported], its presets filed by folder.
     */
    data class ImportMilkdropPack(
        val type: MilkdropPackType,
        val uri: String,
    ) : VisualsWork

    /** Delete the file [file] of [kind] from the MilkDrop folder; answered with [VisualsEvent.MilkdropDeleted]. */
    data class DeleteMilkdrop(
        val kind: MilkdropKind,
        val file: String,
    ) : VisualsWork
}

typealias VisualsEffect = Effect<VisualsWork, VisualsEvent, VisualsFact>
