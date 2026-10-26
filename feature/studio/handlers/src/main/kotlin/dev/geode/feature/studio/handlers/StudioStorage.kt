package dev.geode.feature.studio.handlers

import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderId
import dev.geode.feature.studio.api.RenderJob
import dev.geode.feature.studio.api.RenderSpec
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.core.NewRender

/**
 * What Studio keeps (ADR-0004): the Projects, their videos, the default Project and the Renders (#338),
 * queued and finished, each with its choices and frozen look. Studio owns its
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

    /**
     * Saves [renders], all with [look], as one batch queued at [queuedAt]; returns them waiting, with
     * their ids, in the same order.
     */
    suspend fun queueRenders(
        renders: List<NewRender>,
        look: FrozenLook,
        queuedAt: Millis,
    ): List<RenderJob>

    /** The Renders that have not finished, oldest first: the queue to carry on after a restart. */
    suspend fun unfinishedRenders(): List<RenderJob>

    /** The look frozen for [render], or null when it is not kept. */
    suspend fun lookOf(render: RenderId): FrozenLook?

    /** The look frozen for the Render whose video is [file], or null when no kept Render made it (#327). */
    suspend fun lookOfVideo(file: String): FrozenLook?

    /** [render] was made as [file]: it is kept, with its choices and look, so it can be made again. */
    suspend fun renderSaved(
        render: RenderId,
        file: String,
    )

    /** Forgets [render]: it was cancelled or failed, and left no video. */
    suspend fun forgetRender(render: RenderId)
}

/** [StudioStorage] in Studio's own Room database. */
class RoomStudioStorage(
    private val dao: StudioDao,
) : StudioStorage {
    override suspend fun projects(): List<Project> = dao.projects().map { Project(ProjectId(it.id), it.name, Millis(it.createdAtMs)) }

    /** Every video, each Render with the setup it was made from (#327); a setup that cannot be read is left off. */
    override suspend fun videos(): List<Video> {
        val madeWith = HashMap<String, RenderSpec>()
        for (row in dao.finishedRenders()) {
            val file = row.videoFile ?: continue
            runCatching { RenderSpecFormat.decode(row.spec).toSpec() }.getOrNull()?.let { madeWith[file] = it }
        }
        return dao.videos().map { it.toVideo(madeWith[it.file]) }
    }

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
        return rows.zip(ids).zip(videos) { (row, id), video -> row.copy(id = id).toVideo(video.madeWith) }
    }

    override suspend fun queueRenders(
        renders: List<NewRender>,
        look: FrozenLook,
        queuedAt: Millis,
    ): List<RenderJob> {
        val rows =
            renders.map {
                RenderEntity(batch = 0, spec = RenderSpecFormat.encode(it), look = look.text, videoFile = null, queuedAtMs = queuedAt.value)
            }
        val ids = dao.queueRenders(rows)
        val batch = RenderId(ids.firstOrNull() ?: 0)
        return renders.zip(ids) { render, id -> render.queuedAs(RenderId(id), batch) }
    }

    override suspend fun unfinishedRenders(): List<RenderJob> =
        dao.unfinishedRenders().map { RenderSpecFormat.decode(it.spec).queuedAs(RenderId(it.id), RenderId(it.batch)) }

    override suspend fun lookOf(render: RenderId): FrozenLook? = dao.lookOf(render.value)?.let(::FrozenLook)

    override suspend fun lookOfVideo(file: String): FrozenLook? = dao.lookOfVideo(file)?.let(::FrozenLook)

    override suspend fun renderSaved(
        render: RenderId,
        file: String,
    ) = dao.renderSaved(render.value, file)

    override suspend fun forgetRender(render: RenderId) = dao.forgetRender(render.value)

    private fun VideoEntity.toVideo(madeWith: RenderSpec?) =
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
            madeWith = madeWith,
        )
}
