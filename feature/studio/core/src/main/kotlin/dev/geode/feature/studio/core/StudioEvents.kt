package dev.geode.feature.studio.core

import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderExtras
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderId
import dev.geode.feature.studio.api.RenderJob
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderLook
import dev.geode.feature.studio.api.RenderPhase
import dev.geode.feature.studio.api.RenderProblem
import dev.geode.feature.studio.api.RenderTrack
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

    /** The saved library, with the Renders that had not finished when the app last stopped (#338). */
    data class Loaded(
        val projects: List<Project>,
        val videos: List<Video>,
        val defaultProject: ProjectId?,
        val renders: List<RenderJob> = emptyList(),
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

    /** Settings' Visuals off and Export preferences, now and whenever they change. */
    data class RenderSettingsChanged(
        val settings: RenderSettings,
    ) : StudioEvent

    /** Renders were saved to the queue, with their ids, in the order they were queued. */
    data class RendersQueued(
        val renders: List<RenderJob>,
    ) : StudioEvent

    /** How far the running [render] has got: [progress] 0..1 of it, and the [secondsLeft] when known. */
    data class RenderProgress(
        val render: RenderId,
        val phase: RenderPhase,
        val progress: Float,
        val secondsLeft: Long?,
    ) : StudioEvent

    /** The running [render] stopped: saved, failed or cancelled. */
    data class RenderEnded(
        val render: RenderId,
        val end: RenderEnd,
    ) : StudioEvent
}

/** How a Render ended. */
sealed interface RenderEnd {
    /** Made and saved as [video], which still has to be added to its Project. */
    data class Saved(
        val video: NewVideo,
    ) : RenderEnd

    data class Failed(
        val problem: RenderProblem,
    ) : RenderEnd

    data object Cancelled : RenderEnd
}

/**
 * What Studio's Renders read from Settings: [visualsOff] (no Render while it is on, #196), and what a
 * new Render starts from in Export preferences (#22, #28).
 */
data class RenderSettings(
    val visualsOff: Boolean,
    val format: RenderFormat,
    val extras: RenderExtras,
)

/**
 * A Render about to be queued: a [RenderJob] before Studio's database gives it its id and batch. Every
 * Render queued together has the same [look].
 */
data class NewRender(
    val tracks: List<RenderTrack>,
    val look: RenderLook,
    val format: RenderFormat,
    val length: RenderLength,
    val project: ProjectId,
    val extras: RenderExtras,
) {
    /** It as saved: its [id], and the [batch] it was queued in. It waits until the queue starts it. */
    fun queuedAs(
        id: RenderId,
        batch: RenderId,
    ): RenderJob = RenderJob(id, batch, tracks, look, format, length, project, extras)
}

/** What went wrong in the handlers. */
enum class StudioFailure {
    LOAD,
    SAVE,
    ADD_VIDEOS,
    DELETE_FILES,

    /** The look could not be frozen when a Render was queued. */
    FREEZE_LOOK,

    /** A Render could not be saved to the queue. */
    QUEUE_RENDER,
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

    /** Follow Settings' Visuals off and Export preferences for as long as the app runs. */
    data object FollowRenderSettings : StudioWork

    /**
     * Freeze the [renders]' look once (the full Customization, Automations included) and save them to
     * the queue with it; they come back with their ids in [StudioEvent.RendersQueued].
     */
    data class QueueRenders(
        val renders: List<NewRender>,
    ) : StudioWork

    /** Make [render]'s video with the look frozen for it; it reports its progress, then how it ended. */
    data class Render(
        val render: RenderJob,
    ) : StudioWork

    /** Stop the running [render]: it ends as cancelled and leaves no file. */
    data class StopRender(
        val render: RenderId,
    ) : StudioWork

    /** Forget the waiting [render]: it was cancelled before it started. */
    data class ForgetRender(
        val render: RenderId,
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
        StudioFailure.FREEZE_LOOK -> "Could not read the look for the Render, so it was not queued"
        StudioFailure.QUEUE_RENDER -> "Could not add the Render to the queue"
    }

/** How the reducer describes a Render of [title] that failed with [problem], on the app-wide banner. */
fun describe(
    problem: RenderProblem,
    title: String,
): String {
    val why =
        when (problem) {
            RenderProblem.LOOK_UNAVAILABLE -> "its look could not be read back"
            RenderProblem.TRACK_UNREADABLE -> "the track could not be read"
            RenderProblem.ENCODER_REFUSED -> "the phone would not encode it at this size or frame rate"
            RenderProblem.NOT_SAVED -> "the video could not be saved"
            RenderProblem.ENGINE_FAILED -> "the engine stopped unexpectedly"
        }
    return "The Render of “$title” failed: $why"
}
