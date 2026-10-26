package dev.geode.feature.studio.ui

import androidx.compose.runtime.Immutable
import dev.geode.engine.types.Millis
import dev.geode.engine.types.PresetId
import dev.geode.feature.studio.api.PlaylistMode
import dev.geode.feature.studio.api.Project
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderId
import dev.geode.feature.studio.api.RenderJob
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderPreset
import dev.geode.feature.studio.api.RenderSpec
import dev.geode.feature.studio.api.RenderStatus
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId

/** A playlist the Render setup offers: its [name] and its [tracks], in order. */
data class RenderPlaylist(
    val name: String,
    val tracks: List<RenderTrack>,
)

/** A saved preset the Render setup offers as a look. */
data class LookChoice(
    val preset: PresetId,
    val name: String,
)

/** A MilkDrop preset the Render setup offers: its [file] in the MilkDrop folder and the [name] it is shown by. */
data class MilkdropChoice(
    val file: String,
    val name: String,
)

/**
 * What the Render setup can choose from, which other features own: the Library's [tracks] and
 * [playlists], Customization's saved [presets], and the MilkDrop presets a MilkDrop look can play
 * ([milkdrop], #341). `:app` hands them in, because Studio's screens may reach only Studio (ADR-0005).
 */
@Immutable
data class RenderChoices(
    val tracks: List<RenderTrack> = emptyList(),
    val playlists: List<RenderPlaylist> = emptyList(),
    val presets: List<LookChoice> = emptyList(),
    val milkdrop: List<MilkdropChoice> = emptyList(),
)

/**
 * The Render setup as it is being filled in: the [spec] so far, the platform [preset] it was filled
 * from (null for Custom), and the playlist it renders, if any. When it makes a Render [again] (#327),
 * that video's Track and frozen look are fixed, and [againName] is its name. App UI state, kept by the
 * route until the Render is queued.
 */
@Immutable
data class RenderDraft(
    val spec: RenderSpec,
    val preset: RenderPreset? = null,
    val playlist: String? = null,
    val again: VideoId? = null,
    val againName: String = "",
) {
    /** [spec]'s tracks as one Track, replacing any playlist. */
    fun track(track: RenderTrack): RenderDraft = copy(spec = spec.copy(tracks = listOf(track)), playlist = null)

    fun playlist(playlist: RenderPlaylist): RenderDraft =
        copy(spec = spec.copy(tracks = playlist.tracks, playlist = PlaylistMode.ONE_VIDEO_PER_TRACK), playlist = playlist.name)

    /** [preset]'s values filled in; it shows as chosen until a value is changed by hand. */
    fun preset(preset: RenderPreset): RenderDraft = copy(spec = spec.withPreset(preset), preset = preset)

    /** The MilkDrop preset a MilkDrop look plays, or none for MilkDrop's own idle picture (#341). */
    fun milkdrop(file: String?): RenderDraft = copy(spec = spec.copy(milkdrop = file))

    /** [length] from [start] of the Track, set by hand. */
    fun section(
        start: Millis,
        length: Millis,
    ): RenderDraft = custom(spec.copy(length = RenderLength.Section(start, length)))

    /** A value changed by hand: the format is Custom from now on. */
    fun custom(spec: RenderSpec): RenderDraft = copy(spec = spec, preset = null)

    /** Whether it can be queued: something to render and a Project for it. */
    val ready: Boolean get() = spec.tracks.isNotEmpty() && spec.project != null

    /** What Render queues: a new Render, or the Render [again] made again with these choices. */
    val command: StudioCommand
        get() = again?.let { StudioCommand.RenderAgain(it, spec) } ?: StudioCommand.QueueRender(spec)

    companion object {
        /**
         * "Render again" on [video] (#327): the setup it was made from, to change its shape, quality or
         * frame rate, into its Project, or the default one when that is gone. Null for a video Studio did
         * not render.
         */
        fun again(
            video: Video,
            state: StudioState,
        ): RenderDraft? {
            val made = video.madeWith ?: return null
            val known = state.projects.map { it.id }
            val project = made.project?.takeIf { it in known } ?: state.defaultProject ?: state.projects.firstOrNull()?.id
            return RenderDraft(made.copy(project = project), again = video.id, againName = video.name)
        }

        /** A new setup starts from Settings' Export preferences and the default Project (#22). */
        fun startingFrom(state: StudioState): RenderDraft =
            RenderDraft(
                RenderSpec(
                    tracks = emptyList(),
                    format = state.renderFormat,
                    extras = state.renderExtras,
                    project = state.defaultProject ?: state.projects.firstOrNull()?.id,
                ),
            )
    }
}

/** The Render setup's choices for a Render of the first minute or so, as the short-form presets cap it. */
val FIRST_MINUTE: RenderLength = RenderLength.Section(Millis.ZERO, Millis(59_000))

/** Where "A section" starts when the person first picks it: the first half minute, then changed by hand. */
val FIRST_SECTION: RenderLength.Section = RenderLength.Section(Millis.ZERO, Millis(30_000))

/** What "N minutes" starts from when the person first picks it: an hour, then changed by hand (#346). */
val AN_HOUR: RenderLength.Minutes = RenderLength.Minutes(60)

/** [text] as a number of minutes a Render may last, or null when it is not one (#346). */
fun parseMinutes(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in RenderLength.Minutes.RANGE }

/** Whether [length] is a section the person set by hand, not the whole Track or the first minute. */
fun isOwnSection(length: RenderLength): Boolean = length is RenderLength.Section && length != FIRST_MINUTE

/**
 * [text] as a time: `m:ss`, `h:mm:ss` or whole seconds, as the setup's section fields take it; null when
 * it is not one (a part past the first that is not under 60, or anything but digits and colons).
 */
fun parseClock(text: String): Millis? {
    val parts = text.trim().split(':')
    if (parts.size > MAX_CLOCK_PARTS || parts.any { it.isEmpty() || it.length > MAX_PART_DIGITS || !it.all(Char::isDigit) }) return null
    val numbers = parts.map { it.toLong() }
    if (numbers.drop(1).any { it >= SIXTY }) return null
    val seconds = numbers.fold(0L) { total, part -> total * SIXTY + part }
    return Millis(seconds * MILLIS_PER_SECOND)
}

private const val MAX_CLOCK_PARTS = 3
private const val MAX_PART_DIGITS = 5
private const val SIXTY = 60L
private const val MILLIS_PER_SECOND = 1_000L

/**
 * One group of the render queue: the Renders queued together as [batch] (a playlist's tracks, or one
 * Track), their overall [progress], and which of them can still be cancelled.
 */
data class RenderBatchRow(
    val batch: RenderId,
    val progress: Float,
    val renders: List<RenderJob>,
    val cancellable: Set<RenderId>,
)

/** What the render queue draws; [canClear] when some Renders have finished, failed or been cancelled. */
@Immutable
data class RenderQueueModel(
    val batches: List<RenderBatchRow>,
    val canClear: Boolean,
    val visualsOff: Boolean,
    val projects: List<Project>,
)

fun StudioState.toRenderQueue(): RenderQueueModel {
    val batches =
        renders.groupBy { it.batch }.map { (batch, jobs) ->
            RenderBatchRow(batch, batchProgress(batch), jobs, jobs.filter(::cancellable).map { it.id }.toSet())
        }
    return RenderQueueModel(batches, renders.any { !it.active }, visualsOff, projects)
}

/** The name of [project] among [projects], or blank once it is gone. */
fun projectName(
    projects: List<Project>,
    project: ProjectId?,
): String = projects.firstOrNull { it.id == project }?.name.orEmpty()

private fun cancellable(job: RenderJob): Boolean {
    val status = job.status
    return status == RenderStatus.Queued || (status is RenderStatus.Running && !status.cancelling)
}
