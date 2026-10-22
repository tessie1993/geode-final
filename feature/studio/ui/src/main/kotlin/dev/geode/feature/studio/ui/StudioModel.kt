package dev.geode.feature.studio.ui

import androidx.compose.runtime.Immutable
import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.VideoKind
import java.util.Locale

/** One Project in the Project list, with how many videos it holds. */
data class ProjectRow(
    val project: Project,
    val videos: Int,
)

/** What the Project list draws (ADR-0003: an immutable screen model). */
@Immutable
data class ProjectListModel(
    val loading: Boolean,
    val projects: List<ProjectRow>,
)

/** What a Project's page draws: its videos of [filter]'s kind (all when null), and where they can move. */
@Immutable
data class ProjectModel(
    val project: Project,
    val videos: List<Video>,
    val filter: VideoKind?,
    val others: List<Project>,
)

/** What a video's page draws. */
@Immutable
data class VideoModel(
    val video: Video,
    val project: Project,
    val others: List<Project>,
)

fun StudioState.toProjectList(): ProjectListModel {
    val counts = videos.groupingBy { it.project }.eachCount()
    return ProjectListModel(loading, projects.map { ProjectRow(it, counts[it.id] ?: 0) })
}

/** The page of [id], or null once the Project is gone. */
fun StudioState.toProjectModel(
    id: ProjectId,
    filter: VideoKind?,
): ProjectModel? {
    val project = projects.firstOrNull { it.id == id } ?: return null
    val shown = videosIn(id).filter { filter == null || it.kind == filter }
    return ProjectModel(project, shown, filter, projects - project)
}

/** The page of [id], or null once the video is gone. */
fun StudioState.toVideoModel(id: VideoId): VideoModel? {
    val video = videos.firstOrNull { it.id == id } ?: return null
    val project = projects.firstOrNull { it.id == video.project } ?: return null
    return VideoModel(video, project, projects - project)
}

/** A length as `m:ss`. */
fun Millis.clock(): String {
    val seconds = value / MILLIS_PER_SECOND
    return String.format(Locale.ROOT, "%d:%02d", seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}

private const val MILLIS_PER_SECOND = 1_000L
private const val SECONDS_PER_MINUTE = 60L

/** What the Studio screens ask for. Stateless screens (ADR-0003) send everything here. */
sealed interface StudioAction {
    data class Send(
        val command: StudioCommand,
    ) : StudioAction

    /** Create a Project named [name], in a folder the system folder picker asks for. */
    data class NewProject(
        val name: String,
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

    data object Back : StudioAction
}
