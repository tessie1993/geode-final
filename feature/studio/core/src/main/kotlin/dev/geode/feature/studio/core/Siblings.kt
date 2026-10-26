package dev.geode.feature.studio.core

import dev.geode.engine.types.Millis
import dev.geode.feature.studio.api.EditCommand
import dev.geode.feature.studio.api.EditCut
import dev.geode.feature.studio.api.EditJob
import dev.geode.feature.studio.api.EditSession
import dev.geode.feature.studio.api.EditSource
import dev.geode.feature.studio.api.MadeFrom
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderPreset
import dev.geode.feature.studio.api.RenderSpec
import dev.geode.feature.studio.api.Shorts
import dev.geode.feature.studio.api.StudioCommand
import dev.geode.feature.studio.api.StudioState
import dev.geode.feature.studio.api.Video
import dev.geode.feature.studio.api.VideoId
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.api.edit.ClipEdit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import dev.geode.kernel.ReportFailure

/** What the banner says when a Short or a sibling is refused (#328). */
const val NO_SHORT_PROJECT_REFUSAL = "Choose a Project for the Short first"
const val NO_SIBLING_PROJECT_REFUSAL = "Choose a Project for the new video first"

/** What a Short's name adds to its video's. */
const val SHORT_NAME = "Short"

/**
 * Make Short and Export as (#328, owner decisions on #25): a Short is a 9:16 cut of a Render or
 * Recording under a minute, at the Shorts preset, cropped and trimmed from its source, or drawn again at
 * 9:16 when it is a Render that can be; Export as makes a sibling at another preset, drawing a Render
 * again from its remembered setup, and cropping anything else. Both wait in the render queue and land
 * in the library linked to the video they were made from.
 */
internal object Siblings {
    /** Opens Make Short on [id], with its opening section and the crop in the middle. */
    fun makeShort(
        state: StudioState,
        id: VideoId,
    ): Next<StudioState, StudioEffect> {
        val video = state.videos.firstOrNull { it.id == id } ?: return Next(state)
        val source = EditSource(video.file, video.name, video.length, video.width, video.height)
        val preset = Shorts.PRESET
        val edit =
            ClipEdit(
                endMs = minOf(video.length.value, Shorts.FIRST_SECTION_MS),
                ratio = preset.aspect,
                quality = preset.size ?: ClipEdit().quality,
            )
        return Next(state.copy(edit = EditSession.Short(video.id, source, edit, canRedraw = video.redrawable())))
    }

    /** Make Short's section, crop and choice, unless the section is not one a Short may have. */
    fun changeShort(
        state: StudioState,
        command: EditCommand.ChangeShort,
    ): Next<StudioState, StudioEffect> {
        val session = state.edit as? EditSession.Short ?: return Next(state)
        if (!Shorts.fits(command.startMs, command.endMs, session.source.length.value)) return Next(state)
        val edit =
            session.edit.copy(
                startMs = command.startMs,
                endMs = command.endMs,
                cropX = command.cropX.coerceIn(0f, 1f),
                cropY = command.cropY.coerceIn(0f, 1f),
            )
        return Next(state.copy(edit = session.copy(edit = edit, redraw = command.redraw && session.canRedraw)))
    }

    /**
     * Queues the Short into [project], or its source's Project, or the default one: drawn again at 9:16
     * when the person chose that (refused while Visuals off is on), cropped and trimmed otherwise.
     */
    fun exportShort(
        state: StudioState,
        session: EditSession.Short,
        project: ProjectId?,
    ): Next<StudioState, StudioEffect> {
        val video = state.videos.firstOrNull { it.id == session.video }
        val into =
            state.projectFor(project ?: video?.project) ?: return Next(state, listOf(ReportFailure(NO_SHORT_PROJECT_REFUSAL)))
        val made = MadeFrom(session.source.file, VideoKind.SHORT)
        val shorts = RenderSpec(emptyList(), format = state.renderFormat, extras = state.renderExtras).withPreset(Shorts.PRESET)
        val from = video?.madeWith
        if (!session.redraw || from == null) {
            val name = "${session.source.name.substringBeforeLast('.').ifBlank { CUT_NAME }} $SHORT_NAME"
            val job = EditJob(name, EditCut.OneClip(session.source, session.edit), state.editSources, shorts.extras.loudness)
            return Edits.queueEdit(state, job, shorts.format, shorts.extras, into, made)
        }
        if (state.visualsOff) return Next(state, listOf(ReportFailure(VISUALS_OFF_REFUSAL)))
        val start = (from.length as? RenderLength.Section)?.start?.value ?: 0L
        val length = RenderLength.Section(Millis(start + session.edit.startMs), Millis(session.edit.endMs - session.edit.startMs))
        val extras = from.extras.copy(loudness = shorts.extras.loudness)
        val render = NewRender(from.tracks, from.look, shorts.format, length, into, extras, from.milkdrop, madeFrom = made)
        return Next(state, listOf(Perform(StudioWork.QueueRenderAgain(session.source.file, render))))
    }

    /** Makes a sibling of a video at another preset, in the Project the person chose. */
    fun exportAs(
        state: StudioState,
        command: StudioCommand.ExportAs,
    ): Next<StudioState, StudioEffect> {
        val video = state.videos.firstOrNull { it.id == command.video } ?: return Next(state)
        val into = state.projectFor(command.project) ?: return Next(state, listOf(ReportFailure(NO_SIBLING_PROJECT_REFUSAL)))
        val from = video.madeWith
        if (from != null && !state.visualsOff) {
            val spec = from.withPreset(command.preset)
            // Drawn again, the sibling is a Render of its own, linked to the video it was made from.
            val made = MadeFrom(video.file, VideoKind.RENDER)
            val render = NewRender(from.tracks, from.look, spec.format, spec.length, into, spec.extras, from.milkdrop, madeFrom = made)
            return Next(state, listOf(Perform(StudioWork.QueueRenderAgain(video.file, render))))
        }
        val preset = RenderSpec(emptyList(), format = state.renderFormat, extras = state.renderExtras).withPreset(command.preset)
        val section = command.preset.length as? RenderLength.Section
        val startMs = section?.start?.value ?: 0L
        val endMs = section?.let { minOf(video.length.value, startMs + it.length.value) } ?: 0L
        val edit = ClipEdit(startMs = startMs, endMs = endMs, ratio = command.preset.aspect, quality = preset.format.size)
        val source = EditSource(video.file, video.name, video.length, video.width, video.height)
        val name = "${video.name.substringBeforeLast('.').ifBlank { CUT_NAME }} (${nameOf(command.preset)})"
        val job = EditJob(name, EditCut.OneClip(source, edit), state.editSources, preset.extras.loudness)
        return Edits.queueEdit(state, job, preset.format, preset.extras, into, MadeFrom(video.file, video.kind))
    }

    /** What a preset is called in a sibling's name. */
    fun nameOf(preset: RenderPreset): String =
        when (preset) {
            RenderPreset.TIKTOK_AND_SHORTS -> "TikTok and Shorts"
            RenderPreset.YOUTUBE -> "YouTube"
            RenderPreset.FEED -> "Feed"
            RenderPreset.SQUARE -> "Square"
            RenderPreset.MASTER -> "Master"
        }

    /**
     * Whether a Short of this video can be drawn again at 9:16: a Render of one Track, of the whole Track
     * or a section of it, so the Short's section maps onto the Track.
     */
    private fun Video.redrawable(): Boolean {
        val from = madeWith ?: return false
        val mapsOntoTrack = from.length == RenderLength.WholeTrack || from.length is RenderLength.Section
        return from.tracks.size == 1 && mapsOntoTrack
    }
}
