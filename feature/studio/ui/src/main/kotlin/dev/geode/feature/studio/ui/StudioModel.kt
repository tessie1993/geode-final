package dev.geode.feature.studio.ui

import androidx.compose.runtime.Immutable
import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.SaveFolder
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.VideoKind
import java.net.URLDecoder
import java.util.Locale

/** One Project in the Project list, with how many videos it holds and whether it is the default. */
data class ProjectRow(
    val project: Project,
    val videos: Int,
    val isDefault: Boolean,
)

/**
 * What the Project list draws (ADR-0003: an immutable screen model). [folderMissing] when Settings say
 * no save folder is picked yet; [noDefault] when Projects exist but none is the default.
 */
@Immutable
data class ProjectListModel(
    val loading: Boolean,
    val projects: List<ProjectRow>,
    val folderMissing: Boolean,
    val noDefault: Boolean,
) {
    /** Whether a new Project starts out as the default: when there is none yet. */
    val newIsDefault: Boolean get() = projects.none { it.isDefault }
}

/**
 * What a Project's page draws: its videos of [filter]'s kind (all when null), where they can move, and
 * its delete confirmation.
 */
@Immutable
data class ProjectModel(
    val row: ProjectRow,
    val videos: List<Video>,
    val filter: VideoKind?,
    val others: List<Project>,
    val deletePrompt: DeletePromptModel,
)

/** What a video's page draws. */
@Immutable
data class VideoModel(
    val video: Video,
    val project: Project,
    val others: List<Project>,
)

/**
 * What the delete confirmation draws (owner decision on #21: "Delete N videos too?"). [others] are
 * where the videos can go instead, with [moveTo] picked first: the default Project when it is another
 * one, else the newest other Project; null when there is nowhere else.
 */
@Immutable
data class DeletePromptModel(
    val project: Project,
    val videos: Int,
    val others: List<Project>,
    val moveTo: ProjectId?,
)

/** What Studio's first-run step draws: the folder picked so far, as a readable [folder] label, or null. */
@Immutable
data class StudioSetupModel(
    val folder: String?,
)

fun StudioState.toProjectList(): ProjectListModel {
    val counts = videos.groupingBy { it.project }.eachCount()
    val rows = projects.map { ProjectRow(it, counts[it.id] ?: 0, it.id == defaultProject) }
    return ProjectListModel(
        loading = loading,
        projects = rows,
        folderMissing = saveFolder == SaveFolder.NotChosen,
        noDefault = rows.isNotEmpty() && rows.none { it.isDefault },
    )
}

/** The page of [id], or null once the Project is gone. */
fun StudioState.toProjectModel(
    id: ProjectId,
    filter: VideoKind?,
): ProjectModel? {
    val project = projects.firstOrNull { it.id == id } ?: return null
    val inside = videosIn(id)
    val shown = inside.filter { filter == null || it.kind == filter }
    val row = ProjectRow(project, inside.size, id == defaultProject)
    return ProjectModel(row, shown, filter, projects - project, deletePrompt(row, projects, defaultProject))
}

/** The page of [id], or null once the video is gone. */
fun StudioState.toVideoModel(id: VideoId): VideoModel? {
    val video = videos.firstOrNull { it.id == id } ?: return null
    val project = projects.firstOrNull { it.id == video.project } ?: return null
    return VideoModel(video, project, projects - project)
}

/** The delete confirmation for [row], with [all] the Projects there are and [default] the default one. */
fun deletePrompt(
    row: ProjectRow,
    all: List<Project>,
    default: ProjectId?,
): DeletePromptModel {
    val others = all.filter { it.id != row.project.id }
    val moveTo = others.firstOrNull { it.id == default }?.id ?: others.firstOrNull()?.id
    return DeletePromptModel(row.project, row.videos, others, moveTo)
}

/** The setup step's model for the folder Settings has, if any. */
fun setupModel(folder: String?): StudioSetupModel = StudioSetupModel(folder?.let(::folderLabel))

/**
 * A readable name for a document-tree uri: `…/tree/primary%3AMovies%2FGeode` reads as
 * `Movies/Geode`. Anything else is shown as it is.
 */
fun folderLabel(uri: String): String {
    val tree = uri.substringAfter(TREE_SEGMENT, missingDelimiterValue = "")
    if (tree.isEmpty()) return uri
    val decoded = URLDecoder.decode(tree.substringBefore('/'), UTF_8)
    return decoded.substringAfter(':').ifEmpty { decoded.substringBefore(':') }
}

private const val TREE_SEGMENT = "/tree/"

// The String overload: the Charset one needs Android 13 and Geode runs from 12 (ADR-0004).
private const val UTF_8 = "UTF-8"

/** A length as `m:ss`. */
fun Millis.clock(): String {
    val seconds = value / MILLIS_PER_SECOND
    return String.format(Locale.ROOT, "%d:%02d", seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L

/** What the Studio library screens ask for. Stateless screens (ADR-0003) send everything here. */
sealed interface StudioAction {
    data class Send(
        val command: StudioCommand,
    ) : StudioAction

    data class OpenProject(
        val project: ProjectId,
    ) : StudioAction

    data class OpenVideo(
        val video: VideoId,
    ) : StudioAction

    data class Filter(
        val kind: VideoKind?,
    ) : StudioAction

    /** Pick the save folder with the system folder picker; it is a Settings value. */
    data object PickSaveFolder : StudioAction

    data object Back : StudioAction
}

/** What Studio's first-run step asks for. */
sealed interface StudioSetupAction {
    /** Open the system folder picker. */
    data object PickFolder : StudioSetupAction

    /** The folder is picked: go on with first run. */
    data object Continue : StudioSetupAction
}
