package dev.geode.feature.studio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import dev.geode.feature.studio.api.EditCommand
import dev.geode.feature.studio.api.EditSession
import dev.geode.feature.studio.api.EditSource
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderStatus
import dev.geode.feature.studio.api.Shorts
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.api.edit.ClipEdit
import dev.geode.feature.studio.api.edit.ClipId
import dev.geode.feature.studio.api.edit.EditError
import dev.geode.feature.studio.api.edit.EditorProject
import dev.geode.feature.studio.api.edit.LaneId
import dev.geode.feature.studio.api.edit.MarkerSet
import dev.geode.feature.studio.api.edit.SubtitleCue
import dev.geode.feature.studio.api.edit.TransientEnvelope
import dev.geode.feature.studio.api.edit.TransientSource

/** A chapter file the Timeline can write from its markers, with its type and extension. */
enum class ChapterFile(
    val mimeType: String,
    val extension: String,
) {
    DESCRIPTION("text/plain", "txt"),
    WEB_VTT("text/vtt", "vtt"),
    FFMETADATA("text/plain", "ffmeta"),
}

/** How writing chapters went. */
sealed interface ChapterWrite {
    data object Written : ChapterWrite

    /** There were not markers enough to mark chapters. */
    data object Skipped : ChapterWrite

    data class Failed(
        val message: String,
    ) : ChapterWrite
}

/**
 * What the editors need from the phone that Studio may not reach itself (#318, ADR-0005), which
 * `:app` fills: the GL Transitions' names, the Style the Visual shows, where the player is, the playing
 * song's synced lyrics and its analysed hits, chapter files, and the clip preview and filmstrip frames
 * drawn with the Edit pipeline's own effects.
 */
interface EditorTools {
    /** The names of the GL Transitions a clip can open with. */
    val transitions: List<String>

    /** The Style the live Visual shows, for a new clip on a visual lane. */
    fun currentStyle(): String

    /** Where the player is while it plays, for tapping markers in against the music; null when it is not playing. */
    fun playbackPositionMs(): Long?

    /** The playing song's synced lyrics as captions, or null when it has none. */
    fun lyricCues(): List<SubtitleCue>?

    /** The playing song's analysed hits on [source], or null until it has been analysed. */
    fun transientEnvelope(source: TransientSource): TransientEnvelope?

    /** Writes [markers] as chapters of a cut [durationMs] long, as [file], to the document [uri]. */
    suspend fun writeChapters(
        markers: MarkerSet,
        durationMs: Long,
        file: ChapterFile,
        uri: String,
    ): ChapterWrite

    /** [source] playing in a loop with [edit] applied, as it will export. */
    @Composable
    fun ClipPreview(
        source: EditSource,
        edit: ClipEdit,
        modifier: Modifier,
    )

    /** The frame of the video [file] at [atMs]. */
    @Composable
    fun Frame(
        file: String,
        atMs: Long,
        modifier: Modifier,
    )
}

/** Tools for previews and tests: no transitions, Style, player, lyrics or analysis, and plain frames. */
object NoEditorTools : EditorTools {
    override val transitions: List<String> = emptyList()

    override fun currentStyle(): String = ""

    override fun playbackPositionMs(): Long? = null

    override fun lyricCues(): List<SubtitleCue>? = null

    override fun transientEnvelope(source: TransientSource): TransientEnvelope? = null

    override suspend fun writeChapters(
        markers: MarkerSet,
        durationMs: Long,
        file: ChapterFile,
        uri: String,
    ): ChapterWrite = ChapterWrite.Failed("no chapters here")

    @Composable
    override fun ClipPreview(
        source: EditSource,
        edit: ClipEdit,
        modifier: Modifier,
    ) = Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))

    @Composable
    override fun Frame(
        file: String,
        atMs: Long,
        modifier: Modifier,
    ) = Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
}

/** One Edit in the render queue, as the editors show it: its [name], and how far it got; null while it waits. */
@Immutable
data class EditExportRow(
    val name: String,
    val progress: Float?,
)

/** What the clip editor draws: the [source], the [edit] made so far, and the Edits being exported. */
@Immutable
data class ClipEditorModel(
    val source: EditSource,
    val edit: ClipEdit,
    val exports: List<EditExportRow> = emptyList(),
) {
    /** The video's length, at least a millisecond, so a slider always has a range. */
    val lengthMs: Long get() = source.length.value.coerceAtLeast(1L)

    /** Whether the trim keeps any of the video. */
    val canExport: Boolean get() = edit.trimmedMs(lengthMs) > 0

    /** Whether the edit changes nothing yet. */
    val atDefaults: Boolean get() = edit.isIdentity(lengthMs)

    /** The end of the trim: its out point, or the video's end. */
    val outMs: Long get() = if (edit.endMs > 0) edit.endMs else lengthMs
}

/**
 * What the Timeline draws: the cut, the playhead, whether undo and redo can go, why the last change was
 * refused, and the Edits being exported.
 */
@Immutable
data class TimelineModel(
    val project: EditorProject,
    val playheadMs: Long,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val refused: EditError?,
    val exports: List<EditExportRow> = emptyList(),
)

/**
 * What Make Short draws (#328): the [source] and the [edit] holding its section and crop, whether it
 * can be drawn again rather than cropped ([canRedraw], [redraw]), the [projects] it can land in, with its
 * source's Project ([home]) first chosen, and the Edits being exported.
 */
@Immutable
data class ShortModel(
    val source: EditSource,
    val edit: ClipEdit,
    val canRedraw: Boolean,
    val redraw: Boolean,
    val projects: List<Project>,
    val home: ProjectId?,
    val exports: List<EditExportRow> = emptyList(),
) {
    /** The video's length, at least a millisecond, so a slider always has a range. */
    val lengthMs: Long get() = source.length.value.coerceAtLeast(1L)

    /** How long the Short is. */
    val sectionMs: Long get() = edit.endMs - edit.startMs

    /** Whether the picture is wider than 9:16, so the crop slides across rather than down. */
    val slidesAcross: Boolean get() = source.width.toLong() * SHORT_HIGH > source.height.toLong() * SHORT_WIDE

    /** Whether the section is one a Short may have. */
    val canExport: Boolean get() = Shorts.fits(edit.startMs, edit.endMs, source.length.value)
}

private const val SHORT_WIDE = 9L
private const val SHORT_HIGH = 16L

/**
 * The section a Short's slider asks for, kept to what a Short may have: under a minute, by moving the
 * end along when the start moved ([startMoved]) and the start otherwise; null when it would be under
 * [Shorts.SHORTEST_MS], so the slider stays where it was.
 */
fun shortSection(
    startMs: Long,
    endMs: Long,
    lengthMs: Long,
    startMoved: Boolean,
): Pair<Long, Long>? {
    val start = startMs.coerceIn(0L, lengthMs)
    val end = endMs.coerceIn(0L, lengthMs)
    val longest = Shorts.LIMIT_MS - 1
    val kept =
        when {
            end - start <= longest -> start to end
            startMoved -> start to start + longest
            else -> end - longest to end
        }
    return kept.takeIf { it.second - it.first >= Shorts.SHORTEST_MS }
}

/** The editor on screen: opening, the clip editor, the Timeline, or Make Short. */
sealed interface EditScreenModel {
    /** A picked video or the Timeline's cut is being read; [cut] for the Timeline. */
    data class Opening(
        val cut: Boolean,
    ) : EditScreenModel

    data class OneClip(
        val model: ClipEditorModel,
    ) : EditScreenModel

    data class Cut(
        val model: TimelineModel,
    ) : EditScreenModel

    data class Short(
        val model: ShortModel,
    ) : EditScreenModel
}

/** The editor open in [this] state, or null when none is. */
fun StudioState.toEditModel(): EditScreenModel? =
    when (val open = edit) {
        EditSession.Closed -> null
        EditSession.Reading -> EditScreenModel.Opening(cut = false)
        EditSession.LoadingCut -> EditScreenModel.Opening(cut = true)
        is EditSession.OneClip -> EditScreenModel.OneClip(ClipEditorModel(open.source, open.edit, editExports()))
        is EditSession.Cut ->
            EditScreenModel.Cut(
                TimelineModel(open.project, open.playheadMs, open.history.canUndo, open.history.canRedo, open.refused, editExports()),
            )
        is EditSession.Short ->
            EditScreenModel.Short(
                ShortModel(
                    open.source,
                    open.edit,
                    open.canRedraw,
                    open.redraw,
                    projects,
                    videos.firstOrNull { it.id == open.video }?.project,
                    editExports(),
                ),
            )
    }

/** The Edits waiting or being exported in the render queue, oldest first. */
fun StudioState.editExports(): List<EditExportRow> =
    renders
        .filter { it.edit != null && it.active }
        .map { EditExportRow(it.title, (it.status as? RenderStatus.Running)?.progress) }

/** What a lane's add button picks from the phone's files. */
enum class MediaPick {
    VIDEO,
    STILL,
    OVERLAY,
    AUDIO,
}

/** What the editors ask for. Stateless screens (ADR-0003) send everything here. */
sealed interface EditAction {
    data class Send(
        val command: EditCommand,
    ) : EditAction

    /** Pick a video on the phone to open in the clip editor. */
    data object PickVideo : EditAction

    /** Pick a file of [kind] for a new clip on [lane]. */
    data class PickMedia(
        val lane: LaneId,
        val kind: MediaPick,
    ) : EditAction

    /** Pick a .cube LUT for the clip editor's grade. */
    data object PickLut : EditAction

    data object ImportSrt : EditAction

    data object ExportSrt : EditAction

    data class ExportChapters(
        val file: ChapterFile,
    ) : EditAction
}

/** The sheet or dialog the Timeline has open. */
internal sealed interface TimelineSheet {
    data object Hits : TimelineSheet

    data object Chapters : TimelineSheet

    data class Text(
        val lane: LaneId,
    ) : TimelineSheet

    data class Transition(
        val clip: ClipId,
    ) : TimelineSheet

    /** A new track over [clip], or over the whole cut when it is null. */
    data class Track(
        val clip: ClipId?,
    ) : TimelineSheet
}
