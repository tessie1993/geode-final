package dev.geode.feature.studio.core

import dev.geode.feature.studio.api.EditJob
import dev.geode.feature.studio.api.EditSources
import dev.geode.feature.studio.api.MadeFrom
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
import dev.geode.feature.studio.api.edit.SubtitleCue
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

    /**
     * The old Studio's videos in `Movies/Geode` were imported, once (#347): [videos] were saved into the
     * new [project] made for them.
     */
    data class OldVideosImported(
        val project: Project,
        val videos: List<Video>,
    ) : StudioEvent

    /**
     * What a finished [render]'s extras need was read (#329): its saved [video], and the song's synced
     * [lyrics] in its own time (empty without captions, or when it has none).
     */
    data class FinishReady(
        val render: RenderJob,
        val video: NewVideo,
        val lyrics: List<SubtitleCue>,
    ) : StudioEvent

    /**
     * Projects' folders in the Save folder changed (#340): each of [folders] is now called what it maps
     * to (null: it has none), and each video file or thumbnail in [relinked] moved to the uri it maps to,
     * as when a folder is renamed or a video moves into another Project's folder.
     */
    data class ProjectFoldersChanged(
        val folders: Map<ProjectId, String?>,
        val relinked: Map<String, String> = emptyMap(),
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
 * Edit's export (#318) has its [edit] and no tracks. [madeFrom] is the video a Short, an Export as
 * sibling or a finishing Edit is made from (#328, #329).
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
    val madeFrom: MadeFrom? = null,
) {
    /** It as saved: its [id], and the [batch] it was queued in. It waits until the queue starts it. */
    fun queuedAs(
        id: RenderId,
        batch: RenderId,
    ): RenderJob =
        RenderJob(id, batch, tracks, look, format, length, project, extras, milkdrop = milkdrop, edit = edit, madeFrom = madeFrom)

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
internal fun RenderJob.toNewRender(): NewRender = NewRender(tracks, look, format, length, project, extras, milkdrop, edit, madeFrom)

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

    /** The old Studio's videos could not be brought into the library (#347); it tries again next start. */
    IMPORT_OLD_VIDEOS,

    /** A Project's folder could not be made, renamed or filled in the Save folder (#340). */
    PROJECT_FOLDER,
}

/** Work for Studio's handlers. */
sealed interface StudioWork {
    /** Read the saved library. */
    data object Load : StudioWork

    /** Follow the save folder in Settings > Default paths for as long as the app runs. */
    data object FollowSaveFolder : StudioWork

    /**
     * Save a new Project; its id and creation time come back in [StudioEvent.ProjectCreated], with its
     * folder, made in the Save folder [saveFolder] when one is chosen (#340).
     */
    data class CreateProject(
        val name: String,
        val makeDefault: Boolean,
        val saveFolder: String? = null,
    ) : StudioWork

    /**
     * Rename [project] to [name], and its folder in the Save folder [saveFolder] after it (#340): the new
     * folder name and the files it moved come back in [StudioEvent.ProjectFoldersChanged].
     */
    data class RenameProject(
        val project: ProjectId,
        val name: String,
        val saveFolder: String? = null,
    ) : StudioWork

    data class SetDefaultProject(
        val project: ProjectId,
    ) : StudioWork

    /**
     * Forget [project]: its videos move to [moveTo], or, when that is null, are forgotten too and
     * [files] are deleted (empty when the Project had no videos). A default [project] stops being the
     * default. Its folder in the Save folder [saveFolder] goes too (#340), once the moved videos' files
     * are in [moveTo]'s folder: everything left in it is deleted, except a file another video still uses.
     */
    data class DeleteProject(
        val project: ProjectId,
        val moveTo: ProjectId?,
        val files: List<String>,
        val saveFolder: String? = null,
    ) : StudioWork

    /** Move [video] to [to], and its file into [to]'s folder in the Save folder [saveFolder] (#340). */
    data class MoveVideo(
        val video: VideoId,
        val to: ProjectId,
        val saveFolder: String? = null,
    ) : StudioWork

    /**
     * Make a folder in the Save folder [saveFolder] for every Project that has none yet (#340): those made
     * before Projects had folders, or while no Save folder was chosen. Their names come back in
     * [StudioEvent.ProjectFoldersChanged].
     */
    data class MakeProjectFolders(
        val saveFolder: String,
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

    /**
     * Make [render]'s video with the look frozen for it, into its Project's folder in the Save folder
     * [saveFolder] (#340); it reports its progress, then how it ended.
     */
    data class Render(
        val render: RenderJob,
        val saveFolder: String? = null,
    ) : StudioWork

    /** Stop the running [render]: it ends as cancelled and leaves no file. */
    data class StopRender(
        val render: RenderId,
    ) : StudioWork

    /** Forget the waiting [render]: it was cancelled before it started. */
    data class ForgetRender(
        val render: RenderId,
    ) : StudioWork

    /**
     * Import the old Studio's videos in `Movies/Geode` that the library does not hold yet into a new
     * Project named [OLD_VIDEOS_PROJECT], unless that was done before (#347). Only the first time counts:
     * an import with nothing to bring is done too. What was brought comes back in
     * [StudioEvent.OldVideosImported].
     */
    data object ImportOldVideos : StudioWork

    /**
     * Read the song's synced lyrics, from a local `.lrc` file or its own tags, when the finished [render]
     * wants captions, so its extras are made as an Edit of its [video] (#329); they come back in
     * [StudioEvent.FinishReady].
     */
    data class FinishRender(
        val render: RenderJob,
        val video: NewVideo,
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
        StudioFailure.IMPORT_OLD_VIDEOS -> "Could not bring the earlier Studio's videos into the library"
        StudioFailure.PROJECT_FOLDER -> "Could not make or change a Project's folder in the Save folder"
    }

/** The Project the old Studio's videos are imported into (#347). */
const val OLD_VIDEOS_PROJECT = "Earlier videos"

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
