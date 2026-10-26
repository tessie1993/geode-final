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
     * their ids, in the same order. A continuous video of a playlist (#326) is saved with the look of each
     * of its tracks too, [trackLooks].
     */
    suspend fun queueRenders(
        renders: List<NewRender>,
        look: FrozenLook,
        queuedAt: Millis,
        trackLooks: List<FrozenLook> = emptyList(),
    ): List<RenderJob>

    /** The Renders that have not finished, oldest first: the queue to carry on after a restart. */
    suspend fun unfinishedRenders(): List<RenderJob>

    /** The look frozen for [render], or null when it is not kept. */
    suspend fun lookOf(render: RenderId): FrozenLook?

    /** The look frozen for the Render whose video is [file], or null when no kept Render made it (#327). */
    suspend fun lookOfVideo(file: String): FrozenLook?

    /** The look frozen for each track of [render], a continuous video (#326); empty for any other Render. */
    suspend fun trackLooksOf(render: RenderId): List<FrozenLook>

    /** The look frozen for each track of the continuous video [file] (#326); empty for any other video. */
    suspend fun trackLooksOfVideo(file: String): List<FrozenLook>

    /** [render] was made as [file]: it is kept, with its choices and look, so it can be made again. */
    suspend fun renderSaved(
        render: RenderId,
        file: String,
    )

    /** Forgets [render]: it was cancelled or failed, and left no video. */
    suspend fun forgetRender(render: RenderId)

    /** Whether the one-time import [name] is done (#347). */
    suspend fun imported(name: String): Boolean

    /**
     * Marks the import [name] done; with [videos], saves them first into a new Project named
     * [projectName] created at [createdAt], all together, and returns them. Null when nothing was saved.
     */
    suspend fun import(
        name: String,
        projectName: String,
        createdAt: Millis,
        videos: List<NewVideo>,
    ): ImportedVideos?
}

/** What an import brought into the library (#347): the new [project] and the [videos] in it. */
data class ImportedVideos(
    val project: Project,
    val videos: List<Video>,
)

/** [StudioStorage] in Studio's own Room database. */
class RoomStudioStorage(
    private val dao: StudioDao,
) : StudioStorage {
    override suspend fun projects(): List<Project> = dao.projects().map { Project(ProjectId(it.id), it.name, Millis(it.createdAtMs)) }

    /**
     * Every video, each Render with the setup it was made from (#327); a setup that cannot be read is left
     * off, and so is an Edit's (#318), which is cut from videos and cannot be made again.
     */
    override suspend fun videos(): List<Video> {
        val madeWith = HashMap<String, RenderSpec>()
        for (row in dao.finishedRenders()) {
            val file = row.videoFile ?: continue
            val made = runCatching { RenderSpecFormat.decode(row.spec) }.getOrNull()
            if (made != null && made.edit == null) madeWith[file] = made.toSpec()
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
        val rows = videos.map { it.toEntity(project.value) }
        val ids = dao.insertVideos(rows)
        return rows.zip(ids).zip(videos) { (row, id), video -> row.copy(id = id).toVideo(video.madeWith) }
    }

    override suspend fun queueRenders(
        renders: List<NewRender>,
        look: FrozenLook,
        queuedAt: Millis,
        trackLooks: List<FrozenLook>,
    ): List<RenderJob> {
        val looks = if (trackLooks.isEmpty()) null else TrackLooksFormat.encode(trackLooks)
        val rows =
            renders.map {
                RenderEntity(
                    batch = 0,
                    spec = RenderSpecFormat.encode(it),
                    look = look.text,
                    videoFile = null,
                    queuedAtMs = queuedAt.value,
                    trackLooks = looks,
                )
            }
        val ids = dao.queueRenders(rows)
        val batch = RenderId(ids.firstOrNull() ?: 0)
        return renders.zip(ids) { render, id -> render.queuedAs(RenderId(id), batch) }
    }

    override suspend fun unfinishedRenders(): List<RenderJob> =
        dao.unfinishedRenders().map { RenderSpecFormat.decode(it.spec).queuedAs(RenderId(it.id), RenderId(it.batch)) }

    override suspend fun lookOf(render: RenderId): FrozenLook? = dao.lookOf(render.value)?.let(::FrozenLook)

    override suspend fun lookOfVideo(file: String): FrozenLook? = dao.lookOfVideo(file)?.let(::FrozenLook)

    override suspend fun trackLooksOf(render: RenderId): List<FrozenLook> = TrackLooksFormat.decode(dao.trackLooksOf(render.value))

    override suspend fun trackLooksOfVideo(file: String): List<FrozenLook> = TrackLooksFormat.decode(dao.trackLooksOfVideo(file))

    override suspend fun renderSaved(
        render: RenderId,
        file: String,
    ) = dao.renderSaved(render.value, file)

    override suspend fun forgetRender(render: RenderId) = dao.forgetRender(render.value)

    override suspend fun imported(name: String): Boolean = dao.importsNamed(name) > 0

    override suspend fun import(
        name: String,
        projectName: String,
        createdAt: Millis,
        videos: List<NewVideo>,
    ): ImportedVideos? {
        if (videos.isEmpty()) {
            dao.markImported(ImportEntity(name))
            return null
        }
        val rows = videos.map { it.toEntity(projectId = 0) }
        val saved = dao.importVideos(name, ProjectEntity(name = projectName, createdAtMs = createdAt.value), rows)
        val project = Project(ProjectId(saved.projectId), projectName, createdAt)
        val kept = rows.zip(saved.videoIds) { row, id -> row.copy(id = id, projectId = saved.projectId).toVideo(null) }
        return ImportedVideos(project, kept)
    }

    private fun NewVideo.toEntity(projectId: Long) =
        VideoEntity(
            projectId = projectId,
            kind = kind.name,
            name = name,
            file = file,
            thumbnail = thumbnail,
            lengthMs = length.value,
            width = width,
            height = height,
            song = song,
            sourceId = source?.value,
        )

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
            source = sourceId?.let(::VideoId),
        )
}
