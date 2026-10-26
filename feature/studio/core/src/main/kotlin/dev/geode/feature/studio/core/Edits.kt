package dev.geode.feature.studio.core

import dev.geode.feature.studio.api.EditCommand
import dev.geode.feature.studio.api.EditCut
import dev.geode.feature.studio.api.EditJob
import dev.geode.feature.studio.api.EditSession
import dev.geode.feature.studio.api.EditSource
import dev.geode.feature.studio.api.EditSources
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderLook
import dev.geode.feature.studio.api.SaveFolder
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.edit.Clip
import dev.geode.feature.studio.api.edit.ClipContent
import dev.geode.feature.studio.api.edit.ClipEdit
import dev.geode.feature.studio.api.edit.ClipId
import dev.geode.feature.studio.api.edit.ClipParams
import dev.geode.feature.studio.api.edit.EditError
import dev.geode.feature.studio.api.edit.EditResult
import dev.geode.feature.studio.api.edit.EditorHistory
import dev.geode.feature.studio.api.edit.EditorProject
import dev.geode.feature.studio.api.edit.Keyframe
import dev.geode.feature.studio.api.edit.KeyframeId
import dev.geode.feature.studio.api.edit.KeyframeResult
import dev.geode.feature.studio.api.edit.KeyframeTrack
import dev.geode.feature.studio.api.edit.Lane
import dev.geode.feature.studio.api.edit.LaneId
import dev.geode.feature.studio.api.edit.LaneKind
import dev.geode.feature.studio.api.edit.MarkerId
import dev.geode.feature.studio.api.edit.OverlapPolicy
import dev.geode.feature.studio.api.edit.ParamId
import dev.geode.feature.studio.api.edit.ParamValue
import dev.geode.feature.studio.api.edit.RippleScope
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure

/** What the banner says when an Edit is refused (#318). */
const val NOTHING_TO_EXPORT = "Nothing is left of the clip to export: widen its trim"
const val NO_VIDEO_TO_EXPORT = "The cut has no video to export: put a video or a still on a media lane"
const val NO_EDIT_PROJECT_REFUSAL = "Choose a Project for the Edit first"
const val NO_FOLDER_FOR_COPIES = "Choose a save folder first: the Edit's videos are copied into it"

/** The name of an Edit exported from the Timeline. */
const val CUT_NAME = "Cut"

/** What Studio's handlers find out for the editors (#318). */
sealed interface EditEvent : StudioEvent {
    /** The picked video was read as [source]; null when it could not be read. */
    data class SourceRead(
        val source: EditSource?,
    ) : EditEvent

    /** The Timeline's saved cut, or an empty one when there is none or it could not be read. */
    data class CutLoaded(
        val project: EditorProject,
    ) : EditEvent
}

/** Work for Studio's handlers on an Edit (#318). */
sealed interface EditWork : StudioWork {
    /** Read the length, size and name of the video [file]; [EditEvent.SourceRead] says what was found. */
    data class ReadSource(
        val file: String,
    ) : EditWork

    /** Read the Timeline's saved cut; it comes back in [EditEvent.CutLoaded]. */
    data object LoadCut : EditWork

    /**
     * Keep [project] as the Timeline's cut, so it is there next time. [revision] numbers the saves in the
     * order the changes were made: a save is never written over a newer one.
     */
    data class SaveCut(
        val project: EditorProject,
        val revision: Long,
    ) : EditWork

    /**
     * Save [render], an Edit's export, to the queue; with [EditSources.COPY] its videos are copied into
     * [folder], the Save folder, first and the Edit points at the copies. It comes back with its id in
     * [StudioEvent.RendersQueued].
     */
    data class QueueEdit(
        val render: NewRender,
        val folder: String?,
    ) : EditWork
}

/**
 * Studio's editors as a state machine (#318, ADR-0003): closed, reading a picked video, the clip editor
 * on one video, loading the Timeline's cut, or the Timeline on it. Every change to the cut is one step
 * of undo and is saved behind; a refused one keeps the cut and says why until the next change. Ids for
 * anything new are given here, so the screens stay stateless. Exporting queues the Edit in the render
 * queue (#338) and leaves the editor open.
 */
internal object Edits {
    fun command(
        state: StudioState,
        command: EditCommand,
    ): Next<StudioState, StudioEffect> =
        when (command) {
            is EditCommand.OpenVideo -> openVideo(state, command.video)
            is EditCommand.OpenFile ->
                if (command.file.isBlank()) {
                    Next(state)
                } else {
                    Next(state.copy(edit = EditSession.Reading), listOf(Perform(EditWork.ReadSource(command.file))))
                }
            is EditCommand.ChangeClip -> changeClip(state, command.edit)
            EditCommand.OpenCut ->
                if (state.edit is EditSession.Cut) {
                    Next(state)
                } else {
                    Next(state.copy(edit = EditSession.LoadingCut), listOf(Perform(EditWork.LoadCut)))
                }
            is EditCommand.Export -> export(state, command.project)
            EditCommand.Close -> Next(state.copy(edit = EditSession.Closed))
            else -> onCut(state, command)
        }

    fun event(
        state: StudioState,
        event: EditEvent,
    ): Next<StudioState, StudioEffect> =
        when (event) {
            is EditEvent.SourceRead -> sourceRead(state, event.source)
            is EditEvent.CutLoaded ->
                if (state.edit == EditSession.LoadingCut) {
                    Next(state.copy(edit = EditSession.Cut(EditorHistory(event.project.fitted()))))
                } else {
                    Next(state)
                }
        }

    private fun openVideo(
        state: StudioState,
        id: VideoId,
    ): Next<StudioState, StudioEffect> {
        val video = state.videos.firstOrNull { it.id == id } ?: return Next(state)
        val source = EditSource(video.file, video.name, video.length, video.width, video.height)
        return Next(state.copy(edit = EditSession.OneClip(source)))
    }

    private fun sourceRead(
        state: StudioState,
        source: EditSource?,
    ): Next<StudioState, StudioEffect> =
        when {
            state.edit != EditSession.Reading -> Next(state)
            source == null -> Next(state.copy(edit = EditSession.Closed), listOf(ReportFailure(describe(StudioFailure.READ_VIDEO))))
            else -> Next(state.copy(edit = EditSession.OneClip(source)))
        }

    private fun changeClip(
        state: StudioState,
        edit: ClipEdit,
    ): Next<StudioState, StudioEffect> {
        val session = state.edit as? EditSession.OneClip ?: return Next(state)
        return Next(state.copy(edit = session.copy(edit = edit)))
    }

    /** Queues the open Edit, unless there is nothing to export, no Project, or nowhere to copy its videos. */
    private fun export(
        state: StudioState,
        project: ProjectId?,
    ): Next<StudioState, StudioEffect> {
        val cut = cutOf(state.edit) ?: return Next(state)
        val into = state.projectFor(project)
        val folder = (state.saveFolder as? SaveFolder.Chosen)?.uri
        val refusal =
            when {
                !cut.hasSomethingToExport() -> if (cut is EditCut.OneClip) NOTHING_TO_EXPORT else NO_VIDEO_TO_EXPORT
                into == null -> NO_EDIT_PROJECT_REFUSAL
                state.editSources == EditSources.COPY && folder == null -> NO_FOLDER_FOR_COPIES
                else -> null
            }
        if (refusal != null || into == null) return Next(state, listOf(ReportFailure(refusal ?: NO_EDIT_PROJECT_REFUSAL)))
        val job = EditJob(cut.name(), cut, state.editSources)
        val render =
            NewRender(
                tracks = emptyList(),
                look = RenderLook.Current,
                format = state.renderFormat,
                length = RenderLength.WholeTrack,
                project = into,
                extras = state.renderExtras,
                edit = job,
            )
        return Next(state, listOf(Perform(EditWork.QueueEdit(render, folder))))
    }

    private fun cutOf(session: EditSession): EditCut? =
        when (session) {
            is EditSession.OneClip -> EditCut.OneClip(session.source, session.edit)
            is EditSession.Cut -> EditCut.Cut(session.project)
            EditSession.Closed, EditSession.Reading, EditSession.LoadingCut -> null
        }

    /** The Timeline's own commands: they change the open cut, and do nothing while it is not open. */
    private fun onCut(
        state: StudioState,
        command: EditCommand,
    ): Next<StudioState, StudioEffect> {
        val session = state.edit as? EditSession.Cut ?: return Next(state)
        return when (command) {
            EditCommand.Undo -> step(state, session, session.history.undo())
            EditCommand.Redo -> step(state, session, session.history.redo())
            is EditCommand.MovePlayhead -> Next(state.copy(edit = session.copy(playheadMs = command.atMs.coerceAtLeast(0L))))
            else -> changed(state, session, Cuts.change(session, command))
        }
    }

    private fun step(
        state: StudioState,
        session: EditSession.Cut,
        history: EditorHistory,
    ): Next<StudioState, StudioEffect> {
        if (history === session.history) return Next(state)
        val revision = state.cutRevision + 1
        return Next(
            state.copy(edit = session.copy(history = history, refused = null), cutRevision = revision),
            listOf(Perform(EditWork.SaveCut(history.present, revision))),
        )
    }

    private fun changed(
        state: StudioState,
        session: EditSession.Cut,
        change: Change,
    ): Next<StudioState, StudioEffect> =
        when (change) {
            is Change.Refused -> Next(state.copy(edit = session.copy(refused = change.error)))
            is Change.Made -> {
                val history = session.history.push(change.project)
                if (history === session.history) Next(state) else step(state, session, history)
            }
        }
}

/** What a change to the Timeline's cut came to: the cut [Made], or [Refused] with why. */
internal sealed interface Change {
    data class Made(
        val project: EditorProject,
    ) : Change

    data class Refused(
        val error: EditError,
    ) : Change
}

/** Each Timeline command as a change to the open cut (#318), with the old editor's rules. */
internal object Cuts {
    fun change(
        session: EditSession.Cut,
        command: EditCommand,
    ): Change {
        val project = session.project
        return when (command) {
            is EditCommand.AddLane -> Change.Made(project.withLane(command.kind, command.name.trim()))
            is EditCommand.SetLane -> setLane(project, command)
            is EditCommand.AddClip -> addClip(session, command)
            is EditCommand.AddClips -> Change.Made(project.withClips(command))
            is EditCommand.MoveClip -> applied(project, project.timeline.moveClip(command.clip, command.toMs))
            is EditCommand.TrimClip -> applied(project, project.timeline.trimClip(command.clip, command.edge, command.toMs))
            is EditCommand.SplitClip ->
                applied(project, project.timeline.splitClip(command.clip, command.atMs, ClipId(project.freshClipId())))
            is EditCommand.DeleteClip ->
                applied(
                    project,
                    if (command.ripple) {
                        project.timeline.rippleDeleteClip(command.clip, RippleScope.LANE)
                    } else {
                        project.timeline.deleteClip(command.clip)
                    },
                )
            is EditCommand.DuplicateClip -> applied(project, project.timeline.duplicateClip(command.clip, ClipId(project.freshClipId())))
            is EditCommand.EnableClip -> replaceClip(project, command.clip) { it.copy(enabled = command.enabled) }
            is EditCommand.SetTransition -> replaceClip(project, command.clip) { it.copy(transition = command.transition) }
            else -> Change.Made(Marks.change(project, session.playheadMs, command))
        }
    }

    private fun setLane(
        project: EditorProject,
        command: EditCommand.SetLane,
    ): Change {
        val lane = project.timeline.lane(command.lane) ?: return Change.Refused(EditError.LaneNotFound(command.lane))
        return Change.Made(project.replacingLane(lane.copy(muted = command.muted, locked = command.locked)))
    }

    private fun addClip(
        session: EditSession.Cut,
        command: EditCommand.AddClip,
    ): Change {
        val project = session.project
        val clip =
            Clip(
                ClipId(project.freshClipId()),
                command.content,
                startMs = session.playheadMs,
                durationMs = command.durationMs,
                sourceDurationMs = command.sourceDurationMs,
            )
        return applied(project, project.timeline.addClip(command.lane, clip))
    }

    private fun applied(
        project: EditorProject,
        result: EditResult,
    ): Change =
        when (result) {
            is EditResult.Applied -> Change.Made(project.apply(result).fitted())
            is EditResult.Rejected -> Change.Refused(result.error)
        }

    private fun replaceClip(
        project: EditorProject,
        id: ClipId,
        change: (Clip) -> Clip,
    ): Change {
        val lane = project.timeline.laneOf(id) ?: return Change.Refused(EditError.ClipNotFound(id))
        return Change.Made(project.replacingLane(lane.withClips(lane.clips.map { if (it.id == id) change(it) else it })))
    }
}

/** The Timeline's markers and keyframes as changes to the open cut (#318). */
internal object Marks {
    fun change(
        project: EditorProject,
        playheadMs: Long,
        command: EditCommand,
    ): EditorProject =
        when (command) {
            is EditCommand.AddMarkers -> {
                val taken = project.markers.markers.mapTo(mutableSetOf()) { it.id.value }
                val placed = command.markers.map { it.copy(id = MarkerId(freshId(MARKER, taken).also { id -> taken += id })) }
                project.copy(markers = project.markers.addAll(placed))
            }
            is EditCommand.MoveMarker ->
                if (project.markers.marker(command.marker) == null) {
                    project
                } else {
                    project.copy(markers = project.markers.moveTo(command.marker, command.toMs))
                }
            is EditCommand.DeleteMarker -> project.copy(markers = project.markers.remove(command.marker))
            else -> Keys.change(project, playheadMs, command)
        }
}

/** The Timeline's keyframe tracks as changes to the open cut (#318). */
internal object Keys {
    fun change(
        project: EditorProject,
        playheadMs: Long,
        command: EditCommand,
    ): EditorProject =
        when (command) {
            is EditCommand.AddTrack -> addTrack(project, command.param, command.clip, playheadMs)
            is EditCommand.AddKey -> {
                val track = project.keyframes.track(command.param, command.clip)
                val value = track?.valueAt(command.atMs)
                if (track == null || value == null) project else project.keyed(track.withKey(project.newKey(command.atMs, value)))
            }
            is EditCommand.ChangeKey -> project.keyed(project.keyframes.track(command.param, command.clip)?.withKey(command.key))
            is EditCommand.MoveKey ->
                project.keyed(project.keyframes.track(command.param, command.clip)?.moveKey(command.key, command.toMs))
            is EditCommand.DeleteKey -> {
                val track = project.keyframes.tracks.firstOrNull { it.key(command.key) != null }
                if (track == null) project else project.copy(keyframes = project.keyframes.withTrack(track.removeKey(command.key)))
            }
            else -> project
        }

    /**
     * A new track on [param] for the whole cut, or for [clip] alone, starting with one key at [atMs] that
     * holds the value now, so the curve has something to leave from. A track already there, or a value
     * that is not a clip's, changes nothing.
     */
    private fun addTrack(
        project: EditorProject,
        param: ParamId,
        clip: ClipId?,
        atMs: Long,
    ): EditorProject {
        val known = ClipParams.find(param)
        if (known == null || project.keyframes.track(param, clip) != null) return project
        val content = clip?.let { project.timeline.clip(it)?.content }
        val edit = (content as? ClipContent.Video)?.edit ?: ClipEdit()
        val key = project.newKey(atMs, ParamValue.Scalar(known.read(edit)))
        return project.keyed(KeyframeTrack(paramId = param, clipId = clip).withKey(key))
    }
}

/** The cut it would export has a video or a still on a media lane, or the clip keeps some of its video. */
internal fun EditCut.hasSomethingToExport(): Boolean =
    when (this) {
        is EditCut.OneClip -> edit.trimmedMs(source.length.value) > 0
        is EditCut.Cut -> project.hasVideo()
    }

/** What the Edit is called: the clip's video without its file extension, or [CUT_NAME]. */
internal fun EditCut.name(): String =
    when (this) {
        is EditCut.OneClip -> source.name.substringBeforeLast('.').ifBlank { CUT_NAME }
        is EditCut.Cut -> CUT_NAME
    }

/** Whether a media lane has an enabled video or still: what the Edit pipeline needs to make a video. */
internal fun EditorProject.hasVideo(): Boolean =
    timeline.lanes.any { lane ->
        lane.kind == LaneKind.Media && lane.clips.any { it.enabled && (it.content is ClipContent.Video || it.content is ClipContent.Still) }
    }

/** The programme runs to the end of its last clip; every change re-fits it. */
internal fun EditorProject.fitted(): EditorProject {
    val end = timeline.lanes.maxOfOrNull { lane -> lane.clips.maxOfOrNull { it.endMs } ?: 0L } ?: 0L
    return if (end == timeline.durationMs) this else copy(timeline = timeline.copy(durationMs = end))
}

/**
 * [EditCut]'s videos, sounds, stills, overlays and grade files, each once, in the order they are first
 * used: what Copy copies beside the Edit (#318).
 */
fun EditCut.sources(): List<String> =
    when (this) {
        is EditCut.OneClip -> listOfNotNull(source.file, edit.lutUri)
        is EditCut.Cut -> project.timeline.lanes.flatMap { lane -> lane.clips.flatMap { it.content.sources() } }
    }.distinct()

/** [EditCut] pointing at [copies] of its sources instead of the sources; one not in [copies] stays. */
fun EditCut.relinked(copies: Map<String, String>): EditCut {
    fun copy(of: String): String = copies[of] ?: of
    return when (this) {
        is EditCut.OneClip -> EditCut.OneClip(source.copy(file = copy(source.file)), edit.relinked(copies))
        is EditCut.Cut ->
            EditCut.Cut(
                project.copy(
                    timeline =
                        project.timeline.copy(
                            lanes = project.timeline.lanes.map { lane -> lane.copy(clips = lane.clips.map { it.relinked(copies) }) },
                        ),
                ),
            )
    }
}

private fun ClipEdit.relinked(copies: Map<String, String>): ClipEdit = copy(lutUri = lutUri?.let { copies[it] ?: it })

private fun ClipContent.sources(): List<String> =
    when (this) {
        is ClipContent.Video -> listOfNotNull(uri, edit.lutUri)
        is ClipContent.Still -> listOf(uri)
        is ClipContent.Overlay -> listOf(uri)
        is ClipContent.Audio -> listOf(uri)
        is ClipContent.Scene, is ClipContent.Text -> emptyList()
    }

private fun Clip.relinked(copies: Map<String, String>): Clip {
    val moved =
        when (val shown = content) {
            is ClipContent.Video -> shown.copy(uri = copies[shown.uri] ?: shown.uri, edit = shown.edit.relinked(copies))
            is ClipContent.Still -> shown.copy(uri = copies[shown.uri] ?: shown.uri)
            is ClipContent.Overlay -> shown.copy(uri = copies[shown.uri] ?: shown.uri)
            is ClipContent.Audio -> shown.copy(uri = copies[shown.uri] ?: shown.uri)
            is ClipContent.Scene, is ClipContent.Text -> shown
        }
    return copy(content = moved)
}

/** The first id of [prefix] and a number that [taken] does not hold, counting from past its size. */
internal fun freshId(
    prefix: String,
    taken: Set<String>,
): String {
    var number = taken.size + 1
    while ("$prefix$number" in taken) number++
    return "$prefix$number"
}

private const val LANE = "lane-"
private const val CLIP = "clip-"
private const val MARKER = "marker-"
private const val KEY = "key-"

private fun EditorProject.freshClipId(): String =
    freshId(CLIP, timeline.lanes.flatMapTo(mutableSetOf()) { lane -> lane.clips.map { it.id.value } })

private fun EditorProject.newKey(
    atMs: Long,
    value: ParamValue,
): Keyframe {
    val taken = keyframes.tracks.flatMapTo(mutableSetOf()) { track -> track.keys.map { it.id.value } }
    return Keyframe(KeyframeId(freshId(KEY, taken)), atMs, value)
}

private fun EditorProject.keyed(result: KeyframeResult?): EditorProject =
    when (result) {
        is KeyframeResult.Applied -> copy(keyframes = keyframes.withTrack(result.track))
        is KeyframeResult.Rejected, null -> this
    }

private fun EditorProject.replacingLane(lane: Lane): EditorProject =
    copy(timeline = timeline.copy(lanes = timeline.lanes.map { if (it.id == lane.id) lane else it }))

private fun EditorProject.withLane(
    kind: LaneKind,
    name: String,
): EditorProject {
    val lane = Lane(LaneId(freshId(LANE, timeline.lanes.mapTo(mutableSetOf()) { it.id.value })), kind, name)
    return copy(timeline = timeline.copy(lanes = timeline.lanes + lane))
}

/**
 * [command]'s clips laid over the first lane of its kind, or a new lane when it asks for its own or
 * there is none; each gets a new id, and a clip that cannot be placed is left out.
 */
private fun EditorProject.withClips(command: EditCommand.AddClips): EditorProject {
    if (command.clips.isEmpty()) return this
    val existing = if (command.ownLane) null else timeline.lanes.firstOrNull { it.kind == command.kind }
    val base = if (existing == null) withLane(command.kind, command.laneName.trim()) else this
    val lane = existing ?: base.timeline.lanes.last()
    val placed =
        command.clips.fold(base) { project, clip ->
            val fresh = clip.copy(id = ClipId(project.freshClipId()))
            when (val result = project.timeline.addClip(lane.id, fresh, OverlapPolicy.OVERWRITE)) {
                is EditResult.Applied -> project.apply(result)
                is EditResult.Rejected -> project
            }
        }
    return placed.fitted()
}
