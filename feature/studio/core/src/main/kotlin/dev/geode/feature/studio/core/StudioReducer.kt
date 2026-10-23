package dev.geode.feature.studio.core

import dev.geode.feature.studio.api.ProjectId
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
 */
object StudioReducer : Reducer<StudioState, StudioEvent, StudioEffect> {
    override fun reduce(
        state: StudioState,
        event: StudioEvent,
    ): Next<StudioState, StudioEffect> =
        when (event) {
            is StudioEvent.Command ->
                if (state.loading) Next(state.copy(pending = state.pending + event.command)) else command(state, event.command)
            is StudioEvent.Loaded ->
                ready(state.copy(projects = event.projects, videos = event.videos, defaultProject = event.defaultProject))
            is StudioEvent.SaveFolderChanged -> Next(state.copy(saveFolder = event.folder))
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
        }

    /** The library is read: carry out the Commands that waited for it, in order. */
    private fun ready(state: StudioState): Next<StudioState, StudioEffect> =
        state.pending.fold(Next(state.copy(loading = false, pending = emptyList()))) { next, waiting ->
            val after = command(next.state, waiting)
            Next(after.state, next.effects + after.effects)
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
            is StudioCommand.AddVideos -> add(state, command)
        }

    private fun create(
        state: StudioState,
        command: StudioCommand.CreateProject,
    ): Next<StudioState, StudioEffect> {
        val name = command.name.trim()
        if (name.isEmpty()) return Next(state)
        return Next(state, listOf(Perform(StudioWork.CreateProject(name, command.makeDefault))))
    }

    private fun rename(
        state: StudioState,
        command: StudioCommand.RenameProject,
    ): Next<StudioState, StudioEffect> {
        val name = command.name.trim()
        val project = state.projects.firstOrNull { it.id == command.project }
        if (name.isEmpty() || project == null || project.name == name) return Next(state)
        val projects = state.projects.map { if (it.id == project.id) it.copy(name = name) else it }
        return Next(state.copy(projects = projects), listOf(Perform(StudioWork.RenameProject(project.id, name))))
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
            listOf(Perform(StudioWork.DeleteProject(project.id, moveTo, files))),
        )
    }

    private fun move(
        state: StudioState,
        command: StudioCommand.MoveVideo,
    ): Next<StudioState, StudioEffect> {
        val video = state.videos.firstOrNull { it.id == command.video }
        if (video == null || video.project == command.to || !state.has(command.to)) return Next(state)
        // The record moves; the file stays in the one save folder every Project shares.
        val videos = state.videos.map { if (it.id == video.id) it.copy(project = command.to) else it }
        return Next(state.copy(videos = videos), listOf(Perform(StudioWork.MoveVideo(video.id, command.to))))
    }

    private fun add(
        state: StudioState,
        command: StudioCommand.AddVideos,
    ): Next<StudioState, StudioEffect> {
        if (command.videos.isEmpty()) return Next(state)
        val project = command.project?.takeIf { state.has(it) } ?: state.defaultProject?.takeIf { state.has(it) }
        val effect: StudioEffect =
            if (project == null) Emit(StudioFact.ProjectNeeded(command.videos)) else Perform(StudioWork.AddVideos(project, command.videos))
        return Next(state, listOf(effect))
    }

    private fun StudioState.has(project: ProjectId): Boolean = projects.any { it.id == project }
}
