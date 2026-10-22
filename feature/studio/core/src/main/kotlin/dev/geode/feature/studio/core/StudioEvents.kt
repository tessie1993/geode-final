package dev.geode.feature.studio.core

import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.FilesOnDelete
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.StudioFact
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId
import dev.geode.kernel.Effect

/** A video the old Studio saved, as the phone's media index has it (ADR-0003: built alongside until #17). */
data class OldVideo(
    val file: String,
    val name: String,
    val length: Millis,
    val width: Int,
    val height: Int,
)

/** Everything Studio's reducer handles: Commands, and what its handlers find out. */
sealed interface StudioEvent {
    data class Command(
        val command: StudioCommand,
    ) : StudioEvent

    /** The saved library, and the old Studio's videos on the phone. */
    data class Loaded(
        val projects: List<Project>,
        val videos: List<Video>,
        val oldVideos: List<OldVideo>,
    ) : StudioEvent

    /** A Project was saved with its id and creation time. */
    data class ProjectCreated(
        val project: Project,
    ) : StudioEvent

    /** Old-Studio videos were saved into [project], the "Unsorted" Project. */
    data class Imported(
        val project: Project,
        val videos: List<Video>,
    ) : StudioEvent

    data class Failed(
        val failure: StudioFailure,
    ) : StudioEvent
}

/** What went wrong in the handlers. */
enum class StudioFailure {
    LOAD,
    SAVE,
    IMPORT,
    DELETE_FILES,
}

/** Work for Studio's handlers. */
sealed interface StudioWork {
    /** Read the saved library and look for the old Studio's videos. */
    data object Load : StudioWork

    /** Save a new Project; its id and creation time come back in [StudioEvent.ProjectCreated]. */
    data class CreateProject(
        val name: String,
        val folder: String,
    ) : StudioWork

    data class RenameProject(
        val project: ProjectId,
        val name: String,
    ) : StudioWork

    /**
     * Forget [project] and its videos, give up the permission to its [folder], and delete [files]
     * (empty when the user keeps them).
     */
    data class DeleteProject(
        val project: ProjectId,
        val folder: String?,
        val files: List<String>,
    ) : StudioWork

    data class MoveVideo(
        val video: VideoId,
        val to: ProjectId,
    ) : StudioWork

    /** Save [videos] into [into], or into a new "Unsorted" Project when null. */
    data class Import(
        val into: Project?,
        val videos: List<OldVideo>,
    ) : StudioWork
}

typealias StudioEffect = Effect<StudioWork, StudioEvent, StudioFact>

/** How the reducer describes a failure on the app-wide banner (ADR-0004). */
fun describe(failure: StudioFailure): String =
    when (failure) {
        StudioFailure.LOAD -> "Could not read the Studio library"
        StudioFailure.SAVE -> "Could not save the change to the Studio library"
        StudioFailure.IMPORT -> "Could not add the old Studio videos"
        StudioFailure.DELETE_FILES -> "Some video files could not be deleted"
    }

/** Whether [files] said to delete. */
internal val FilesOnDelete.deletes: Boolean get() = this == FilesOnDelete.DELETE
