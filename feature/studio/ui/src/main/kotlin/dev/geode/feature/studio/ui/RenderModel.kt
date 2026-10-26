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
import dev.geode.feature.studio.api.StudioState

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

/**
 * What the Render setup can choose from, which other features own: the Library's [tracks] and
 * [playlists], and Customization's saved [presets]. `:app` hands them in, because Studio's screens may
 * reach only Studio (ADR-0005).
 */
@Immutable
data class RenderChoices(
    val tracks: List<RenderTrack> = emptyList(),
    val playlists: List<RenderPlaylist> = emptyList(),
    val presets: List<LookChoice> = emptyList(),
)

/**
 * The Render setup as it is being filled in: the [spec] so far, the platform [preset] it was filled
 * from (null for Custom), and the playlist it renders, if any. App UI state, kept by the route until
 * the Render is queued.
 */
@Immutable
data class RenderDraft(
    val spec: RenderSpec,
    val preset: RenderPreset? = null,
    val playlist: String? = null,
) {
    /** [spec]'s tracks as one Track, replacing any playlist. */
    fun track(track: RenderTrack): RenderDraft = copy(spec = spec.copy(tracks = listOf(track)), playlist = null)

    fun playlist(playlist: RenderPlaylist): RenderDraft =
        copy(spec = spec.copy(tracks = playlist.tracks, playlist = PlaylistMode.ONE_VIDEO_PER_TRACK), playlist = playlist.name)

    /** [preset]'s values filled in; it shows as chosen until a value is changed by hand. */
    fun preset(preset: RenderPreset): RenderDraft = copy(spec = spec.withPreset(preset), preset = preset)

    /** [length] from [start] of the Track, set by hand. */
    fun section(
        start: Millis,
        length: Millis,
    ): RenderDraft = custom(spec.copy(length = RenderLength.Section(start, length)))

    /** A value changed by hand: the format is Custom from now on. */
    fun custom(spec: RenderSpec): RenderDraft = copy(spec = spec, preset = null)

    /** Whether it can be queued: something to render and a Project for it. */
    val ready: Boolean get() = spec.tracks.isNotEmpty() && spec.project != null

    companion object {
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
