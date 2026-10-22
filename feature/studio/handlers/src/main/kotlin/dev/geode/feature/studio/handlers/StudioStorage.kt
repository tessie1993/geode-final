package dev.geode.feature.studio.handlers

import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.VideoKind

/**
 * What Studio keeps (ADR-0004): the Projects, their videos and the default Project. Studio owns its
 * Projects, so the default Project is kept here, not in Settings. [RoomStudioStorage] keeps them in
 * Studio's own Room database. Any call may throw; the handlers turn that into a named failure.
 */
interface StudioStorage {
    /** Every Project, the newest first. */
    suspend fun projects(): List<Project>

    suspend fun videos(): List<Video>

    /** The default Project, or null when none is chosen. */
    suspend fun defaultProject(): ProjectId?

    suspend fun createProject(
        name: String,
        createdAt: Millis,
        makeDefault: Boolean,
    ): Project

    suspend fun renameProject(
        project: ProjectId,
        name: String,
    )

    suspend fun setDefaultProject(project: ProjectId)

    /** Forgets [project]: its videos move to [moveTo], or are forgotten too when it is null. */
    suspend fun deleteProject(
        project: ProjectId,
        moveTo: ProjectId?,
    )

    suspend fun moveVideo(
        video: VideoId,
        to: ProjectId,
    )

    /** Saves [videos] into [project], returning them with their ids, in the same order. */
    suspend fun addVideos(
        project: ProjectId,
        videos: List<NewVideo>,
    ): List<Video>
}

/** [StudioStorage] in Studio's own Room database. */
class RoomStudioStorage(
    private val dao: StudioDao,
) : StudioStorage {
    override suspend fun projects(): List<Project> = dao.projects().map { Project(ProjectId(it.id), it.name, Millis(it.createdAtMs)) }

    override suspend fun videos(): List<Video> = dao.videos().map { it.toVideo() }

    override suspend fun defaultProject(): ProjectId? = dao.defaultProject()?.let(::ProjectId)

    override suspend fun createProject(
        name: String,
        createdAt: Millis,
        makeDefault: Boolean,
    ): Project {
        val id = dao.createProject(ProjectEntity(name = name, createdAtMs = createdAt.value), makeDefault)
        return Project(ProjectId(id), name, createdAt)
    }

    override suspend fun renameProject(
        project: ProjectId,
        name: String,
    ) = dao.renameProject(project.value, name)

    override suspend fun setDefaultProject(project: ProjectId) = dao.setChoices(StudioChoiceEntity(defaultProjectId = project.value))

    override suspend fun deleteProject(
        project: ProjectId,
        moveTo: ProjectId?,
    ) = dao.deleteProject(project.value, moveTo?.value)

    override suspend fun moveVideo(
        video: VideoId,
        to: ProjectId,
    ) = dao.moveVideo(video.value, to.value)

    override suspend fun addVideos(
        project: ProjectId,
        videos: List<NewVideo>,
    ): List<Video> {
        val rows =
            videos.map {
                VideoEntity(
                    projectId = project.value,
                    kind = it.kind.name,
                    name = it.name,
                    file = it.file,
                    thumbnail = it.thumbnail,
                    lengthMs = it.length.value,
                    width = it.width,
                    height = it.height,
                    song = it.song,
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
