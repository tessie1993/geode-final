package dev.geode.feature.visuals.core

import dev.geode.engine.types.SceneId
import dev.geode.feature.visuals.api.ImportReport
import dev.geode.feature.visuals.api.MilkdropKind
import dev.geode.feature.visuals.api.MilkdropPreset
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

    /** The MilkDrop folder was listed: the [presets], built-in ones among them, and the [textures]. */
    data class MilkdropListed(
        val presets: List<MilkdropPreset>,
        val textures: List<String>,
    ) : VisualsEvent

    /** An import finished as [report] says, and left the MilkDrop folder holding [presets] and [textures]. */
    data class MilkdropImported(
        val report: ImportReport,
        val presets: List<MilkdropPreset>,
        val textures: List<String>,
    ) : VisualsEvent

    /** A piece of work failed at the handlers' edge; the reducer reports it. */
    data class WorkFailed(
        val failure: WorkFailure,
    ) : VisualsEvent
}

/** Work the handlers could not carry out, named for the log and the app-wide banner. */
enum class WorkFailure(
    val message: String,
) {
    LIBRARY_NOT_LOADED("could not read the saved Favourites, Recent and folders"),
    LIBRARY_NOT_SAVED("could not save the Favourites, Recent and folders"),
    STYLE_NOT_CHOSEN("could not switch the Visual to the chosen Style"),
    MILKDROP_NOT_LISTED("could not list the MilkDrop presets and textures"),
    MILKDROP_NOT_IMPORTED("could not import into the MilkDrop folder"),
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
}

typealias VisualsEffect = Effect<VisualsWork, VisualsEvent, VisualsFact>
