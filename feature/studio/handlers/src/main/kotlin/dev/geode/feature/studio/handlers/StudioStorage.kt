package dev.geode.feature.studio.handlers

import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.core.OldVideo

/**
 * What Studio keeps (ADR-0004): the Projects and their videos. [RoomStudioStorage] keeps them in
 * Studio's own Room database. Any call may throw; the handlers turn that into a named failure.
 */
interface StudioStorage {
    /** Every Project, the newest first. */
    suspend fun projects(): List<Project>

    suspend fun videos(): List<Video>

    suspend fun createProject(
        name: String,
        folder: String?,
        createdAt: Millis,
    ): Project

    suspend fun renameProject(
        project: ProjectId,
        name: String,
    )

    /** Forgets [project] and its videos. */
    suspend fun deleteProject(project: ProjectId)

    suspend fun moveVideo(
        video: VideoId,
        to: ProjectId,
    )

    /** Saves the old Studio's [videos] as Renders in [into]. */
    suspend fun addOldVideos(
        into: ProjectId,
        videos: List<OldVideo>,
    ): List<Video>
}

/** [StudioStorage] in Studio's own Room database. */
class RoomStudioStorage(
    private val dao: StudioDao,
) : StudioStorage {
    override suspend fun projects(): List<Project> =
        dao.projects().map { Project(ProjectId(it.id), it.name, it.folder, Millis(it.createdAtMs)) }

    override suspend fun videos(): List<Video> = dao.videos().map { it.toVideo() }

    override suspend fun createProject(
        name: String,
        folder: String?,
        createdAt: Millis,
    ): Project {
        val id = dao.insertProject(ProjectEntity(name = name, folder = folder, createdAtMs = createdAt.value))
        return Project(ProjectId(id), name, folder, createdAt)
    }

    override suspend fun renameProject(
        project: ProjectId,
        name: String,
    ) = dao.renameProject(project.value, name)

    override suspend fun deleteProject(project: ProjectId) = dao.deleteProject(project.value)

    override suspend fun moveVideo(
        video: VideoId,
        to: ProjectId,
    ) = dao.moveVideo(video.value, to.value)

    override suspend fun addOldVideos(
        into: ProjectId,
        videos: List<OldVideo>,
    ): List<Video> {
        val rows =
            videos.map {
                VideoEntity(
                    projectId = into.value,
                    // The old Studio saved finished exports: Renders.
                    kind = VideoKind.RENDER.name,
                    name = it.name,
                    file = it.file,
                    thumbnail = null,
                    lengthMs = it.length.value,
                    width = it.width,
                    height = it.height,
                    song = "",
                )
            }
        val ids = dao.insertVideos(rows)
        return rows.zip(ids) { row, id -> row.copy(id = id).toVideo() }
    }

    private fun VideoEntity.toVideo() =
        Video(
            id = VideoId(id),
            project = ProjectId(projectId),
            kind = VideoKind.valueOf(kind),
            name = name,
            file = file,
            thumbnail = thumbnail,
            length = Millis(lengthMs),
            width = width,
            height = height,
            song = song,
        )
}
