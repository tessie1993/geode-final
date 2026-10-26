package dev.geode.feature.studio.api

import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.edit.Clip
import dev.geode.feature.studio.api.edit.ClipContent
import dev.geode.feature.studio.api.edit.ClipEdge
import dev.geode.feature.studio.api.edit.ClipEdit
import dev.geode.feature.studio.api.edit.ClipId
import dev.geode.feature.studio.api.edit.ClipTransition
import dev.geode.feature.studio.api.edit.EditError
import dev.geode.feature.studio.api.edit.EditorHistory
import dev.geode.feature.studio.api.edit.EditorProject
import dev.geode.feature.studio.api.edit.Keyframe
import dev.geode.feature.studio.api.edit.KeyframeId
import dev.geode.feature.studio.api.edit.LaneId
import dev.geode.feature.studio.api.edit.LaneKind
import dev.geode.feature.studio.api.edit.Marker
import dev.geode.feature.studio.api.edit.MarkerId
import dev.geode.feature.studio.api.edit.ParamId

/**
 * What a saved Edit does with the videos it is cut from (#318), as Settings > Export preferences says:
 * [REFERENCE] points at them where they are; [COPY] copies them into the Save folder beside the Edit,
 * so it still exports after a source is moved or deleted.
 */
enum class EditSources {
    REFERENCE,
    COPY,
}

/**
 * A video the clip editor cuts (#318): a Render or Recording in the library, or any video file the
 * person picked. [file] is a content uri; [length] its length, [width] and [height] its size.
 */
data class EditSource(
    val file: String,
    val name: String,
    val length: Millis,
    val width: Int,
    val height: Int,
)

/**
 * What an Edit is cut from, and how (#318): one video cut in the clip editor, or the Timeline's cut of
 * clips, transitions and text. Text belongs to the Edit, never to the Visual.
 */
sealed interface EditCut {
    /** [source] with the clip editor's [edit]: its trim, loop, speed, look and caption. */
    data class OneClip(
        val source: EditSource,
        val edit: ClipEdit,
    ) : EditCut

    /** The Timeline's [project]: its lanes of clips, their transitions and text, markers and keyframes. */
    data class Cut(
        val project: EditorProject,
    ) : EditCut
}

/**
 * An Edit the render queue exports (#318): its [name], its [cut], and whether its sources were copied
 * beside it when it was saved ([sources]). Its video lands in its Render's Project as an Edit.
 */
data class EditJob(
    val name: String,
    val cut: EditCut,
    val sources: EditSources,
)

/**
 * The editor open in Studio (#318), if any: the clip editor on one video, or the Timeline. The screens
 * only draw it and send [EditCommand]s; Studio's reducer decides every change.
 */
sealed interface EditSession {
    data object Closed : EditSession

    /** A picked video file is being read before the clip editor opens on it. */
    data object Reading : EditSession

    /** The clip editor on [source], with the [edit] made so far. */
    data class OneClip(
        val source: EditSource,
        val edit: ClipEdit = ClipEdit(),
    ) : EditSession

    /** The Timeline's saved cut is being read. */
    data object LoadingCut : EditSession

    /**
     * The Timeline on [history]'s present cut, with its undo and redo, the playhead at [playheadMs], and
     * why the last change was refused ([refused]), until the next one is made. [name] is the earlier
     * editor's cut it was opened on (#347), kept under that name; null for the Timeline's own cut.
     */
    data class Cut(
        val history: EditorHistory,
        val playheadMs: Long = 0L,
        val refused: EditError? = null,
        val name: String? = null,
    ) : EditSession {
        val project: EditorProject get() = history.present
    }
}

/**
 * What Studio's editors ask for (#318). The Timeline's changes apply to the open cut only, and each is
 * one step of undo; the ids of anything new are the Edit's to give. Opening an editor closes the one
 * that was open; the Timeline's cut is kept as it goes.
 */
sealed interface EditCommand : StudioCommand {
    /** Opens the clip editor on [video], a video in the library. */
    data class OpenVideo(
        val video: VideoId,
    ) : EditCommand

    /** Opens the clip editor on [file], a video the person picked; it is read first. */
    data class OpenFile(
        val file: String,
    ) : EditCommand

    /** The clip editor's edit is now [edit]. */
    data class ChangeClip(
        val edit: ClipEdit,
    ) : EditCommand

    /** Opens the Timeline on its saved cut. */
    data object OpenCut : EditCommand

    /**
     * Opens the Timeline on [name], one of the cuts the earlier editor kept ([StudioState.savedCuts],
     * #347); its changes are kept under that name.
     */
    data class OpenSavedCut(
        val name: String,
    ) : EditCommand

    /** A new lane of [kind], named [name], under the others. */
    data class AddLane(
        val kind: LaneKind,
        val name: String,
    ) : EditCommand

    data class SetLane(
        val lane: LaneId,
        val muted: Boolean,
        val locked: Boolean,
    ) : EditCommand

    /**
     * A clip of [content], [durationMs] long, on [lane] at the playhead; [sourceDurationMs] is how much
     * material a video or sound has, 0 for content with no length of its own.
     */
    data class AddClip(
        val lane: LaneId,
        val content: ClipContent,
        val durationMs: Long,
        val sourceDurationMs: Long = 0L,
    ) : EditCommand

    /**
     * [clips] laid over the first lane of [kind], or over a new one named [laneName] when [ownLane] or
     * when there is none; whatever they cover is overwritten. Lyric captions and imported subtitles
     * come on a lane of their own, auto-cut clips over the first visual lane.
     */
    data class AddClips(
        val kind: LaneKind,
        val laneName: String,
        val clips: List<Clip>,
        val ownLane: Boolean,
    ) : EditCommand

    data class MoveClip(
        val clip: ClipId,
        val toMs: Long,
    ) : EditCommand

    data class TrimClip(
        val clip: ClipId,
        val edge: ClipEdge,
        val toMs: Long,
    ) : EditCommand

    data class SplitClip(
        val clip: ClipId,
        val atMs: Long,
    ) : EditCommand

    /** Deletes [clip]; with [ripple] the gap it leaves on its lane closes. */
    data class DeleteClip(
        val clip: ClipId,
        val ripple: Boolean = false,
    ) : EditCommand

    /** A copy of [clip] just after it. */
    data class DuplicateClip(
        val clip: ClipId,
    ) : EditCommand

    data class EnableClip(
        val clip: ClipId,
        val enabled: Boolean,
    ) : EditCommand

    /** The transition [clip] opens with from the clip before it on its lane; null cuts. */
    data class SetTransition(
        val clip: ClipId,
        val transition: ClipTransition?,
    ) : EditCommand

    /** Places [markers], each with a new id; a tap-in pass or auto-cut adds many at once. */
    data class AddMarkers(
        val markers: List<Marker>,
    ) : EditCommand

    data class MoveMarker(
        val marker: MarkerId,
        val toMs: Long,
    ) : EditCommand

    data class DeleteMarker(
        val marker: MarkerId,
    ) : EditCommand

    /** Animates [param] over the whole cut, or over [clip] alone, starting from its value at the playhead. */
    data class AddTrack(
        val param: ParamId,
        val clip: ClipId?,
    ) : EditCommand

    /** A key on [param]'s track at [atMs], holding the value the track has there. */
    data class AddKey(
        val param: ParamId,
        val clip: ClipId?,
        val atMs: Long,
    ) : EditCommand

    /** The key [key] names on [param]'s track is now [key]: its value or its curve changed. */
    data class ChangeKey(
        val param: ParamId,
        val clip: ClipId?,
        val key: Keyframe,
    ) : EditCommand

    data class MoveKey(
        val param: ParamId,
        val clip: ClipId?,
        val key: KeyframeId,
        val toMs: Long,
    ) : EditCommand

    data class DeleteKey(
        val key: KeyframeId,
    ) : EditCommand

    data object Undo : EditCommand

    data object Redo : EditCommand

    data class MovePlayhead(
        val atMs: Long,
    ) : EditCommand

    /**
     * Queues the open Edit for export into [project], or the default Project: it waits in the render
     * queue like a Render and lands in the library as an Edit. The editor stays open.
     */
    data class Export(
        val project: ProjectId? = null,
    ) : EditCommand

    data object Close : EditCommand
}
