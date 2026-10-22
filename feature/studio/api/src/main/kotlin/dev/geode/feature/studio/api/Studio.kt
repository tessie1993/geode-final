package dev.geode.feature.studio.api

import dev.geode.engine.types.Millis
import dev.geode.kernel.Feature

/**
 * Studio as everything outside it sees it (ADR-0003, ADR-0005): the Projects and the videos inside
 * them, and the Commands that change them (#21). Rendering, recording, editing and Shorts add to this
 * feature (#22–#26).
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
 * A named folder that groups its videos. [folder] is the folder the user picked with the system
 * folder picker, as a persisted tree uri; null for [UNSORTED_NAME], whose old-Studio videos stay where
 * the old Studio saved them.
 */
data class Project(
    val id: ProjectId,
    val name: String,
    val folder: String?,
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

/**
 * One video, in exactly one [project]. [file] and [thumbnail] are content uris; [song] is the title of
 * the song it was made to, blank when there was none or it is not known.
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
) {
    val shape: Shape
        get() =
            when {
                width > height -> Shape.LANDSCAPE
                width < height -> Shape.PORTRAIT
                else -> Shape.SQUARE
            }
}

/** The Project old-Studio videos go into (owner decision on #21). */
const val UNSORTED_NAME = "Unsorted"

/** The library: every Project, the newest first, and every video. [loading] until the saved library is read. */
data class StudioState(
    val loading: Boolean = true,
    val projects: List<Project> = emptyList(),
    val videos: List<Video> = emptyList(),
) {
    fun videosIn(project: ProjectId): List<Video> = videos.filter { it.project == project }
}

/** What deleting a Project does with its videos' files. Nothing is deleted silently. */
enum class FilesOnDelete {
    KEEP,
    DELETE,
}

sealed interface StudioCommand {
    /** [folder] is the tree uri the system folder picker returned, already persisted by the caller. */
    data class CreateProject(
        val name: String,
        val folder: String,
    ) : StudioCommand

    data class RenameProject(
        val project: ProjectId,
        val name: String,
    ) : StudioCommand

    data class DeleteProject(
        val project: ProjectId,
        val files: FilesOnDelete,
    ) : StudioCommand

    data class MoveVideo(
        val video: VideoId,
        val to: ProjectId,
    ) : StudioCommand
}

/** One-off facts Studio owns. */
sealed interface StudioFact {
    data class ProjectCreated(
        val project: ProjectId,
    ) : StudioFact
}
