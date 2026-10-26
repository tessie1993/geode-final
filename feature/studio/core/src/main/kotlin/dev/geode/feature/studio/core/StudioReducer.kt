package dev.geode.feature.studio.core

import dev.geode.feature.studio.api.EditCommand
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RecordingCommand
import dev.geode.feature.studio.api.SaveFolder
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.StudioFact
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.api.VideosOnDelete
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.Reducer
import dev.geode.kernel.ReportFailure

/**
 * Studio's state machine for Projects and the library (#21).
 *
 * The library is read once (loading → ready). Commands that arrive before then wait and are carried
 * out in order once it is read, so a video handed over at start-up is never lost. Renames, moves,
 * deletes and the default Project show at once and are saved behind; a new Project and new videos
 * show once they are saved, with their ids. The save folder follows Settings at any time.
 *
 * Owner decisions on #21: the user chooses the default Project (there is no built-in one); deleting
 * a Project that still has videos asks "Delete N videos too?" and either deletes them or moves them to
 * another Project; videos that come without a Project go to the default one, and with no default
 * they are handed back to be asked about, never dropped.
 *
 * Owner decision on #340: each Project has its own folder in the Save folder. A Project gets it when it
 * is made, or, made before folders or before a Save folder was chosen, once the library and a Save
 * folder are both known; a rename renames it, a moved video's file moves with it, and a deleted
 * Project's folder goes. The handlers report the folders' names and the files they moved.
 *
 * The render queue's rules are [RenderQueue]'s (#338); the saved Renders that had not finished come
 * back with the library and start once it is read. Recordings of the live Visual are [Recordings]'
 * (#317), and the editors are [Edits]' (#318).
 */
object StudioReducer : Reducer<StudioState, StudioEvent, StudioEffect> {
    override fun reduce(
        state: StudioState,
        event: StudioEvent,
    ): Next<StudioState, StudioEffect> =
        when (event) {
            is StudioEvent.Command ->
                if (state.loading) Next(state.copy(pending = state.pending + event.command)) else command(state, event.command)
            is StudioEvent.Loaded -> {
                val read =
                    ready(
                        state.copy(
                            projects = event.projects,
                            videos = event.videos,
                            defaultProject = event.defaultProject,
                            renders = event.renders,
                        ),
                    )
                // With the library known, the old Studio's videos and editor cuts can be brought in (#347).
                Next(read.state, read.effects + Perform(StudioWork.ImportOldVideos) + Perform(EditWork.ListCuts) + folders(read.state))
            }
            is StudioEvent.OldVideosImported ->
                Next(
                    state.copy(projects = listOf(event.project) + state.projects, videos = state.videos + event.videos),
                    listOf(Emit(StudioFact.VideosAdded(event.project.id, event.videos.map { it.id }))),
                )
            is StudioEvent.SaveFolderChanged -> state.copy(saveFolder = event.folder).let { Next(it, folders(it)) }
            is StudioEvent.ProjectFoldersChanged -> Next(foldersChanged(state, event))
            is StudioEvent.ProjectCreated ->
                Next(
                    state.copy(
                        projects = listOf(event.project) + state.projects,
                        defaultProject = if (event.isDefault) event.project.id else state.defaultProject,
                    ),
                    listOf(Emit(StudioFact.ProjectCreated(event.project.id))),
                )
            is StudioEvent.VideosAdded ->
                Next(
                    state.copy(videos = state.videos + event.videos),
                    listOf(Emit(StudioFact.VideosAdded(event.project, event.videos.map { it.id }))),
                )
            is StudioEvent.Failed -> failed(state, event.failure)
            is StudioEvent.RenderSettingsChanged -> RenderQueue.settings(state, event.settings)
            is StudioEvent.AppInFront -> RenderQueue.next(state.copy(appInFront = event.inFront))
            is StudioEvent.RendersQueued -> RenderQueue.queued(state, event.renders)
            is StudioEvent.RenderProgress -> RenderQueue.progress(state, event)
            is StudioEvent.RenderEnded -> RenderQueue.ended(state, event)
            is StudioEvent.FinishReady -> RenderQueue.finish(state, event)
            is RecordingEvent -> Recordings.event(state, event)
            is EditEvent -> Edits.event(state, event)
        }

    /** Making the folders of the Projects that have none, once the library and a Save folder are known (#340). */
    private fun folders(state: StudioState): List<StudioEffect> {
        val saveFolder = state.saveFolderUri
        if (state.loading || saveFolder == null || state.projects.all { it.folder != null }) return emptyList()
        return listOf(Perform(StudioWork.MakeProjectFolders(saveFolder)))
    }

    private fun foldersChanged(
        state: StudioState,
        event: StudioEvent.ProjectFoldersChanged,
    ): StudioState {
        val projects = state.projects.map { if (it.id in event.folders) it.copy(folder = event.folders[it.id]) else it }
        val moved = event.relinked
        val videos =
            state.videos.map {
                it.copy(
                    file = moved[it.file] ?: it.file,
                    thumbnail =
                        it.thumbnail?.let { old ->
                            moved[old] ?: old
                        },
                )
            }
        return state.copy(projects = projects, videos = videos)
    }

    /** The library is read: carry out the Commands that waited for it, in order, then start the queue. */
    private fun ready(state: StudioState): Next<StudioState, StudioEffect> {
        val drained: Next<StudioState, StudioEffect> =
            state.pending.fold(Next(state.copy(loading = false, pending = emptyList()))) { next, waiting ->
                val after = command(next.state, waiting)
                Next(after.state, next.effects + after.effects)
            }
        val started = RenderQueue.next(drained.state)
        return Next(started.state, drained.effects + started.effects)
    }

    private fun failed(
        state: StudioState,
        failure: StudioFailure,
    ): Next<StudioState, StudioEffect> {
        val report = ReportFailure(describe(failure))
        if (failure != StudioFailure.LOAD || !state.loading) return Next(state, listOf(report))
        // Without the saved library there is nothing to show but an empty one; what waited still runs.
        val next = ready(state)
        return Next(next.state, listOf(report) + next.effects)
    }

    private fun command(
        state: StudioState,
        command: StudioCommand,
    ): Next<StudioState, StudioEffect> =
        when (command) {
            is StudioCommand.CreateProject -> create(state, command)
            is StudioCommand.RenameProject -> rename(state, command)
            is StudioCommand.SetDefaultProject -> setDefault(state, command.project)
            is StudioCommand.DeleteProject -> delete(state, command)
            is StudioCommand.MoveVideo -> move(state, command)
            is StudioCommand.AddVideos -> addVideos(state, command.videos, command.project)
            is StudioCommand.QueueRender -> RenderQueue.queue(state, command.spec)
            is StudioCommand.RenderAgain -> RenderQueue.again(state, command.video, command.spec)
            is StudioCommand.CancelRenders -> RenderQueue.cancel(state, command.renders)
            StudioCommand.ClearFinishedRenders -> RenderQueue.clearFinished(state)
            is StudioCommand.ExportAs -> Siblings.exportAs(state, command)
            is RecordingCommand -> Recordings.command(state, command)
            is EditCommand -> Edits.command(state, command)
        }

    private fun create(
        state: StudioState,
        command: StudioCommand.CreateProject,
    ): Next<StudioState, StudioEffect> {
        val name = command.name.trim()
        if (name.isEmpty()) return Next(state)
        return Next(state, listOf(Perform(StudioWork.CreateProject(name, command.makeDefault, state.saveFolderUri))))
    }

    private fun rename(
        state: StudioState,
        command: StudioCommand.RenameProject,
    ): Next<StudioState, StudioEffect> {
        val name = command.name.trim()
        val project = state.projects.firstOrNull { it.id == command.project }
        if (name.isEmpty() || project == null || project.name == name) return Next(state)
        val projects = state.projects.map { if (it.id == project.id) it.copy(name = name) else it }
        return Next(state.copy(projects = projects), listOf(Perform(StudioWork.RenameProject(project.id, name, state.saveFolderUri))))
    }

    private fun setDefault(
        state: StudioState,
        project: ProjectId,
    ): Next<StudioState, StudioEffect> {
        if (state.defaultProject == project || !state.has(project)) return Next(state)
        return Next(state.copy(defaultProject = project), listOf(Perform(StudioWork.SetDefaultProject(project))))
    }

    private fun delete(
        state: StudioState,
        command: StudioCommand.DeleteProject,
    ): Next<StudioState, StudioEffect> {
        val project = state.projects.firstOrNull { it.id == command.project } ?: return Next(state)
        val (inside, others) = state.videos.partition { it.project == project.id }
        val choice = command.videos
        val moveTo = if (inside.isEmpty()) null else (choice as? VideosOnDelete.MoveTo)?.project
        // The videos can only move to another Project that exists.
        if (moveTo != null && (moveTo == project.id || !state.has(moveTo))) return Next(state)
        val videos = if (moveTo == null) others else state.videos.map { if (it.project == project.id) it.copy(project = moveTo) else it }
        val files = if (moveTo == null) inside.map { it.file } else emptyList()
        return Next(
            state.copy(
                projects = state.projects - project,
                videos = videos,
                defaultProject = state.defaultProject.takeIf { it != project.id },
            ),
            listOf(Perform(StudioWork.DeleteProject(project.id, moveTo, files, state.saveFolderUri))),
        )
    }

    private fun move(
        state: StudioState,
        command: StudioCommand.MoveVideo,
    ): Next<StudioState, StudioEffect> {
        val video = state.videos.firstOrNull { it.id == command.video }
        if (video == null || video.project == command.to || !state.has(command.to)) return Next(state)
        // The record moves at once; its file follows into the other Project's folder behind (#340).
        val videos = state.videos.map { if (it.id == video.id) it.copy(project = command.to) else it }
        return Next(state.copy(videos = videos), listOf(Perform(StudioWork.MoveVideo(video.id, command.to, state.saveFolderUri))))
    }

    private fun StudioState.has(project: ProjectId): Boolean = projects.any { it.id == project }
}

/** The chosen Save folder's uri, or null while none is (#340). */
internal val StudioState.saveFolderUri: String? get() = (saveFolder as? SaveFolder.Chosen)?.uri

/** [project] when it exists, otherwise the default Project when one is chosen; null with neither. */
internal fun StudioState.projectFor(project: ProjectId?): ProjectId? {
    val known = projects.map { it.id }
    return project?.takeIf { it in known } ?: defaultProject?.takeIf { it in known }
}

/**
 * Saves [videos] into [project], or the default Project when [project] is null or gone; with neither,
 * hands them back to be asked about.
 */
internal fun addVideos(
    state: StudioState,
    videos: List<NewVideo>,
    project: ProjectId?,
): Next<StudioState, StudioEffect> {
    if (videos.isEmpty()) return Next(state)
    val into = state.projectFor(project)
    val effect: StudioEffect = if (into == null) Emit(StudioFact.ProjectNeeded(videos)) else Perform(StudioWork.AddVideos(into, videos))
    return Next(state, listOf(effect))
}
