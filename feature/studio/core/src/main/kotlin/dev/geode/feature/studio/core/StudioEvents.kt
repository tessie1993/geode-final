package dev.geode.feature.studio.core

import dev.geode.feature.studio.api.EditJob
import dev.geode.feature.studio.api.EditSources
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.PlaylistMode
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
import dev.geode.feature.studio.api.RenderSpec
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

    /** The app came in front of the person, or left (#28): with Renders keep going off, the queue follows it. */
    data class AppInFront(
        val inFront: Boolean,
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
 * What Studio's Renders read from Settings: [visualsOff] (no Render while it is on, #196), what a new
 * Render starts from in Export preferences (#22, #28), Run in background's Renders keep going switch
 * ([keepGoing], #28), and what a saved Edit does with its videos ([editSources], #318).
 */
data class RenderSettings(
    val visualsOff: Boolean,
    val format: RenderFormat,
    val extras: RenderExtras,
    val keepGoing: Boolean = true,
    val editSources: EditSources = EditSources.REFERENCE,
)

/**
 * A Render about to be queued: a [RenderJob] before Studio's database gives it its id and batch. Every
 * Render queued together has the same [look]; [milkdrop] is the MilkDrop preset it plays (#341). An
 * Edit's export (#318) has its [edit] and no tracks.
 */
data class NewRender(
    val tracks: List<RenderTrack>,
    val look: RenderLook,
    val format: RenderFormat,
    val length: RenderLength,
    val project: ProjectId,
    val extras: RenderExtras,
    val milkdrop: String? = null,
    val edit: EditJob? = null,
) {
    /** It as saved: its [id], and the [batch] it was queued in. It waits until the queue starts it. */
    fun queuedAs(
        id: RenderId,
        batch: RenderId,
    ): RenderJob = RenderJob(id, batch, tracks, look, format, length, project, extras, milkdrop = milkdrop, edit = edit)

    /** The setup it was made from, as its video keeps it to be made again (#327). */
    fun toSpec(): RenderSpec =
        RenderSpec(
            tracks = tracks,
            look = look,
            format = format,
            length = length,
            project = project,
            playlist = if (tracks.size > 1) PlaylistMode.ONE_CONTINUOUS_VIDEO else PlaylistMode.ONE_VIDEO_PER_TRACK,
            extras = extras,
            milkdrop = milkdrop,
        )
}

/** [this] Render before it was queued: its choices without its id, batch or status. */
internal fun RenderJob.toNewRender(): NewRender = NewRender(tracks, look, format, length, project, extras, milkdrop, edit)

/** What went wrong in the handlers. */
enum class StudioFailure {
    LOAD,
    SAVE,
    ADD_VIDEOS,
    DELETE_FILES,

    /** The look could not be frozen when a Render was queued, or read back to make one again. */
    FREEZE_LOOK,

    /** A Render could not be saved to the queue. */
    QUEUE_RENDER,

    /** The video picked for the clip editor could not be read (#318). */
    READ_VIDEO,

    /** The Timeline's saved cut could not be read; it opens empty (#318). */
    LOAD_CUT,

    /** A change to the Timeline's cut could not be saved (#318). */
    SAVE_CUT,

    /** An Edit's videos could not be copied beside it, so it was not queued (#318). */
    COPY_SOURCES,
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

    /** Follow whether the app is in front of the person, for as long as it runs (#28). */
    data object FollowAppInFront : StudioWork

    /**
     * Freeze the [renders]' look once (the full Customization, Automations included) and save them to
     * the queue with it; they come back with their ids in [StudioEvent.RendersQueued].
     */
    data class QueueRenders(
        val renders: List<NewRender>,
    ) : StudioWork

    /**
     * Queue [render] with the look frozen for the Render whose video is [file] (#327); it comes back with
     * its id in [StudioEvent.RendersQueued].
     */
    data class QueueRenderAgain(
        val file: String,
        val render: NewRender,
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
        StudioFailure.READ_VIDEO -> "Could not read that video"
        StudioFailure.LOAD_CUT -> "Could not read the saved cut, so the Timeline starts empty"
        StudioFailure.SAVE_CUT -> "Could not save the last change to the cut"
        StudioFailure.COPY_SOURCES -> "Could not copy the Edit's videos, so it was not exported"
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
