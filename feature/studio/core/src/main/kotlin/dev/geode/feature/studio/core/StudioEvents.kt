package dev.geode.feature.studio.core

import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.SaveFolder
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.StudioFact
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId
import dev.geode.kernel.Effect

/** Everything Studio's reducer handles: Commands, and what its handlers find out. */
sealed interface StudioEvent {
    data class Command(
        val command: StudioCommand,
    ) : StudioEvent

    /** The saved library. */
    data class Loaded(
        val projects: List<Project>,
        val videos: List<Video>,
        val defaultProject: ProjectId?,
    ) : StudioEvent

    /** Settings' save folder, now and whenever it changes. */
    data class SaveFolderChanged(
        val folder: SaveFolder,
    ) : StudioEvent

    /** A Project was saved with its id and creation time; [isDefault] when it was also made the default. */
    data class ProjectCreated(
        val project: Project,
        val isDefault: Boolean,
    ) : StudioEvent

    /** Videos were saved into a Project, with their ids. */
    data class VideosAdded(
        val project: ProjectId,
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
    ADD_VIDEOS,
    DELETE_FILES,
}

/** Work for Studio's handlers. */
sealed interface StudioWork {
    /** Read the saved library. */
    data object Load : StudioWork

    /** Follow the save folder in Settings > Default paths for as long as the app runs. */
    data object FollowSaveFolder : StudioWork

    /** Save a new Project; its id and creation time come back in [StudioEvent.ProjectCreated]. */
    data class CreateProject(
        val name: String,
        val makeDefault: Boolean,
    ) : StudioWork

    data class RenameProject(
        val project: ProjectId,
        val name: String,
    ) : StudioWork

    data class SetDefaultProject(
        val project: ProjectId,
    ) : StudioWork

    /**
     * Forget [project]: its videos move to [moveTo], or, when that is null, are forgotten too and
     * [files] are deleted (empty when the Project had no videos). A default [project] stops being the
     * default.
     */
    data class DeleteProject(
        val project: ProjectId,
        val moveTo: ProjectId?,
        val files: List<String>,
    ) : StudioWork

    data class MoveVideo(
        val video: VideoId,
        val to: ProjectId,
    ) : StudioWork

    /** Save [videos] into [project]; they come back with their ids in [StudioEvent.VideosAdded]. */
    data class AddVideos(
        val project: ProjectId,
        val videos: List<NewVideo>,
    ) : StudioWork
}

typealias StudioEffect = Effect<StudioWork, StudioEvent, StudioFact>

/** How the reducer describes a failure on the app-wide banner (ADR-0004). */
fun describe(failure: StudioFailure): String =
    when (failure) {
        StudioFailure.LOAD -> "Could not read the Studio library"
        StudioFailure.SAVE -> "Could not save the change to the Studio library"
        StudioFailure.ADD_VIDEOS -> "Could not add the new videos to the Studio library"
        StudioFailure.DELETE_FILES -> "Some video files could not be deleted"
    }
