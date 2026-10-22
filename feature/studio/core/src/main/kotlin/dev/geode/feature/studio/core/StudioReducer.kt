package dev.geode.feature.studio.core

import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.StudioFact
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.api.UNSORTED_NAME
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.Reducer
import dev.geode.kernel.ReportFailure

/**
 * Studio's state machine for Projects and the library (#21). The library is read once (loading →
 * ready); Commands that arrive before then are dropped, as the screens show nothing to act on yet.
 * Renames, moves and deletes show at once and are saved behind; a new Project shows once it is saved,
 * with its id. Old-Studio videos not in any Project yet go into the "Unsorted" Project.
 */
object StudioReducer : Reducer<StudioState, StudioEvent, StudioEffect> {
    override fun reduce(
        state: StudioState,
        event: StudioEvent,
    ): Next<StudioState, StudioEffect> =
        when (event) {
            is StudioEvent.Command -> if (state.loading) Next(state) else command(state, event.command)
            is StudioEvent.Loaded -> loaded(event)
            is StudioEvent.ProjectCreated ->
                Next(
                    state.copy(projects = listOf(event.project) + state.projects),
                    listOf(Emit(StudioFact.ProjectCreated(event.project.id))),
                )
            is StudioEvent.Imported -> imported(state, event)
            is StudioEvent.Failed -> failed(state, event.failure)
        }

    private fun loaded(event: StudioEvent.Loaded): Next<StudioState, StudioEffect> {
        val state = StudioState(loading = false, projects = event.projects, videos = event.videos)
        val known = event.videos.mapTo(HashSet()) { it.file }
        val fresh = event.oldVideos.filter { it.file !in known }
        if (fresh.isEmpty()) return Next(state)
        val unsorted = event.projects.firstOrNull(::isUnsorted)
        return Next(state, listOf(Perform(StudioWork.Import(unsorted, fresh))))
    }

    private fun imported(
        state: StudioState,
        event: StudioEvent.Imported,
    ): Next<StudioState, StudioEffect> {
        val projects = if (state.projects.any { it.id == event.project.id }) state.projects else state.projects + event.project
        return Next(state.copy(projects = projects, videos = state.videos + event.videos))
    }

    private fun failed(
        state: StudioState,
        failure: StudioFailure,
    ): Next<StudioState, StudioEffect> {
        // Without the saved library there is nothing to show but an empty one.
        val shown = if (failure == StudioFailure.LOAD) state.copy(loading = false) else state
        return Next(shown, listOf(ReportFailure(describe(failure))))
    }

    private fun command(
        state: StudioState,
        command: StudioCommand,
    ): Next<StudioState, StudioEffect> =
        when (command) {
            is StudioCommand.CreateProject -> create(state, command)
            is StudioCommand.RenameProject -> rename(state, command)
            is StudioCommand.DeleteProject -> delete(state, command)
            is StudioCommand.MoveVideo -> move(state, command)
        }

    private fun create(
        state: StudioState,
        command: StudioCommand.CreateProject,
    ): Next<StudioState, StudioEffect> {
        val name = command.name.trim()
        if (name.isEmpty() || command.folder.isBlank()) return Next(state)
        return Next(state, listOf(Perform(StudioWork.CreateProject(name, command.folder))))
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

    private fun delete(
        state: StudioState,
        command: StudioCommand.DeleteProject,
    ): Next<StudioState, StudioEffect> {
        val project = state.projects.firstOrNull { it.id == command.project } ?: return Next(state)
        val (gone, kept) = state.videos.partition { it.project == project.id }
        val files = if (command.files.deletes) gone.map { it.file } else emptyList()
        return Next(
            state.copy(projects = state.projects - project, videos = kept),
            listOf(Perform(StudioWork.DeleteProject(project.id, project.folder, files))),
        )
    }

    private fun move(
        state: StudioState,
        command: StudioCommand.MoveVideo,
    ): Next<StudioState, StudioEffect> {
        val video = state.videos.firstOrNull { it.id == command.video }
        if (video == null || video.project == command.to || state.projects.none { it.id == command.to }) return Next(state)
        // ponytail: the record moves, the file stays in its folder; copy it across SAF trees if users ask.
        val videos = state.videos.map { if (it.id == video.id) it.copy(project = command.to) else it }
        return Next(state.copy(videos = videos), listOf(Perform(StudioWork.MoveVideo(video.id, command.to))))
    }

    private fun isUnsorted(project: Project): Boolean = project.folder == null && project.name == UNSORTED_NAME
}
