package dev.geode.feature.studio.api

import dev.geode.engine.types.Millis
import dev.geode.kernel.Feature

/**
 * Studio as everything outside it sees it (ADR-0003, ADR-0005): the Projects, the videos inside them,
 * the default Project and the save folder, the render queue, the Recording and the open editor, and the
 * Commands that change them (#21, #338, #317, #318). Shorts add to this feature (#25).
 */
typealias Studio = Feature<StudioState, StudioCommand, StudioFact>

@JvmInline
value class ProjectId(
    val value: Long,
) {
    override fun toString(): String = "project $value"
}

@JvmInline
value class VideoId(
    val value: Long,
) {
    override fun toString(): String = "video $value"
}

/**
 * A Project the user created and named (`CONTEXT.md`). It groups videos; it has no folder of its own,
 * because every video is saved to the one [SaveFolder] (owner decision on #21).
 */
data class Project(
    val id: ProjectId,
    val name: String,
    val createdAt: Millis,
)

/** What a video is (`CONTEXT.md`). */
enum class VideoKind {
    RENDER,
    RECORDING,
    EDIT,
    SHORT,
}

enum class Shape {
    LANDSCAPE,
    PORTRAIT,
    SQUARE,
}

/** A video's shape from its size. */
fun shapeOf(
    width: Int,
    height: Int,
): Shape =
    when {
        width > height -> Shape.LANDSCAPE
        width < height -> Shape.PORTRAIT
        else -> Shape.SQUARE
    }

/**
 * A finished video that is not in the library yet, as whatever made it hands it to Studio with
 * [StudioCommand.AddVideos]: a Render (#22), a Recording (#23), an Edit or Short (#24, #25), or an old
 * Studio export imported once after the upgrade (#347). [file] and [thumbnail] are content uris; [song]
 * is the title of the song it was made to, blank when there was none or it is not known. [madeWith] is,
 * for a Render Studio made, the setup it was made from, so it can be made again (#327); null otherwise.
 */
data class NewVideo(
    val kind: VideoKind,
    val name: String,
    val file: String,
    val thumbnail: String?,
    val length: Millis,
    val width: Int,
    val height: Int,
    val song: String,
    val madeWith: RenderSpec? = null,
)

/**
 * One video in the library, in exactly one [project]. The fields are [NewVideo]'s: a Render Studio
 * made keeps the setup it was made from in [madeWith], and can be made again at another shape or
 * quality with [StudioCommand.RenderAgain] (#327).
 */
data class Video(
    val id: VideoId,
    val project: ProjectId,
    val kind: VideoKind,
    val name: String,
    val file: String,
    val thumbnail: String?,
    val length: Millis,
    val width: Int,
    val height: Int,
    val song: String,
    val madeWith: RenderSpec? = null,
) {
    val shape: Shape get() = shapeOf(width, height)
}

/**
 * The one folder Studio saves every video to. It is a Settings value (Settings > Default paths,
 * #101) that Studio follows through Settings' interface; it is picked in Studio's first-run step.
 */
sealed interface SaveFolder {
    /** Settings are still being read. */
    data object Unknown : SaveFolder

    /** Nothing picked yet: Studio's first-run step is due. */
    data object NotChosen : SaveFolder

    /** [uri] is the persisted document-tree uri the system folder picker returned. */
    data class Chosen(
        val uri: String,
    ) : SaveFolder
}

/**
 * The library. [loading] until the saved library is read; Commands sent meanwhile wait in [pending]
 * and are carried out, in order, once it is. [projects] are the newest first. [defaultProject] is the
 * Project the user chose for videos that arrive without one; null when none is chosen.
 *
 * [renders] is the render queue (#338): what waits and runs, oldest first, and what finished since the
 * app started. [visualsOff] is Settings' Visuals off, while which no Render can be made (#196).
 * [renderFormat] and [renderExtras] are what a new Render starts from: Settings > Export preferences.
 * [recording] is the Recording of the live Visual, if one runs or waits for its Project (#317).
 * [rendersKeepGoing] is Settings' Renders keep going switch (#28), and [appInFront] whether the app is
 * what the person sees: with the switch off, Renders only run while the app is in front. [edit] is the
 * editor open on an Edit (#318), and [editSources] Settings' choice of what a saved Edit does with its
 * videos. [cutRevision] counts the changes to the Timeline's cut saved since the app started: each save
 * carries its number, so a slower, older save is never written over a newer one. [savedCuts] are the
 * names of the cuts the earlier editor kept besides the Timeline's own, which the Timeline opens (#347).
 */
data class StudioState(
    val loading: Boolean = true,
    val projects: List<Project> = emptyList(),
    val videos: List<Video> = emptyList(),
    val defaultProject: ProjectId? = null,
    val saveFolder: SaveFolder = SaveFolder.Unknown,
    val pending: List<StudioCommand> = emptyList(),
    val renders: List<RenderJob> = emptyList(),
    val visualsOff: Boolean = false,
    val renderFormat: RenderFormat = RenderFormat(),
    val renderExtras: RenderExtras = RenderExtras(),
    val recording: RecordingState = RecordingState.Idle,
    val rendersKeepGoing: Boolean = true,
    val appInFront: Boolean = true,
    val edit: EditSession = EditSession.Closed,
    val editSources: EditSources = EditSources.REFERENCE,
    val cutRevision: Long = 0L,
    val savedCuts: List<String> = emptyList(),
) {
    /** Whether the queue waits for the app to come back: Renders keep going is off and it is not in front. */
    val rendersHeld: Boolean get() = !rendersKeepGoing && !appInFront

    fun videosIn(project: ProjectId): List<Video> = videos.filter { it.project == project }

    /** Whether Studio's first-run step should be shown: Settings are read and no save folder is picked. */
    val needsSetup: Boolean get() = saveFolder == SaveFolder.NotChosen

    /** The Render being made now, if any: the queue makes one at a time. */
    val runningRender: RenderJob? get() = renders.firstOrNull { it.status is RenderStatus.Running }

    /**
     * How far the Renders queued together as [batch] (a playlist's tracks) have got, 0..1: a waiting
     * one counts as not started, and a finished, failed or cancelled one as done. 0 for no Renders.
     */
    fun batchProgress(batch: RenderId): Float {
        val inBatch = renders.filter { it.batch == batch }
        if (inBatch.isEmpty()) return 0f
        return inBatch.sumOf { it.progressPart().toDouble() }.toFloat() / inBatch.size
    }

    private fun RenderJob.progressPart(): Float =
        when (val now = status) {
            RenderStatus.Queued -> 0f
            is RenderStatus.Running -> now.progress
            RenderStatus.Done, RenderStatus.Cancelled, is RenderStatus.Failed -> 1f
        }
}

/** What deleting a Project does with the videos still in it. Nothing is ever deleted silently. */
sealed interface VideosOnDelete {
    /** "Delete N videos too?" Yes: the videos and their files go with the Project. */
    data object Delete : VideosOnDelete

    /** No: the videos move to [project] and their files stay. */
    data class MoveTo(
        val project: ProjectId,
    ) : VideosOnDelete
}

sealed interface StudioCommand {
    /** A new Project named [name]; [makeDefault] also makes it the default Project. */
    data class CreateProject(
        val name: String,
        val makeDefault: Boolean,
    ) : StudioCommand

    data class RenameProject(
        val project: ProjectId,
        val name: String,
    ) : StudioCommand

    /** The user's choice of default Project. */
    data class SetDefaultProject(
        val project: ProjectId,
    ) : StudioCommand

    /** [videos] says what happens to the videos still in it; it is not looked at when there are none. */
    data class DeleteProject(
        val project: ProjectId,
        val videos: VideosOnDelete,
    ) : StudioCommand

    data class MoveVideo(
        val video: VideoId,
        val to: ProjectId,
    ) : StudioCommand

    /**
     * Puts finished videos into [project], or into the default Project when [project] is null or no
     * longer exists. With neither, nothing is added and [StudioFact.ProjectNeeded] hands the videos
     * back, so the caller asks the user which Project; nothing is dropped silently.
     */
    data class AddVideos(
        val videos: List<NewVideo>,
        val project: ProjectId? = null,
    ) : StudioCommand

    /**
     * Queues the Render [spec] describes (#338): one Render per track, or one for a whole playlist,
     * into the chosen Project, or the default one. The look is frozen as it is queued. Refused, with
     * a word on the banner, while Visuals off is on or when there is no Project for it.
     */
    data class QueueRender(
        val spec: RenderSpec,
    ) : StudioCommand

    /**
     * Makes the Render [video] again (#327): a new Render of the same Track with the look frozen for
     * [video], and [spec]'s format, length, Project, extras and MilkDrop preset. [spec]'s tracks, look
     * and playlist are not looked at. The new video is its own Render, and nothing about it changes what
     * the next fresh Render of the song starts from. Refused as [QueueRender] is; a video that is not a
     * Render Studio made does nothing.
     */
    data class RenderAgain(
        val video: VideoId,
        val spec: RenderSpec,
    ) : StudioCommand

    /** Cancels [renders]: a waiting one never starts, and a running one stops and leaves no file. */
    data class CancelRenders(
        val renders: Set<RenderId>,
    ) : StudioCommand

    /** Takes the finished, failed and cancelled Renders off the queue; their videos stay where they are. */
    data object ClearFinishedRenders : StudioCommand
}

/** One-off facts Studio owns. */
sealed interface StudioFact {
    data class ProjectCreated(
        val project: ProjectId,
    ) : StudioFact

    /** [videos] were saved into [project]. */
    data class VideosAdded(
        val project: ProjectId,
        val videos: List<VideoId>,
    ) : StudioFact

    /** [videos] came without a Project while no default Project is chosen: ask the user where they go. */
    data class ProjectNeeded(
        val videos: List<NewVideo>,
    ) : StudioFact
}
