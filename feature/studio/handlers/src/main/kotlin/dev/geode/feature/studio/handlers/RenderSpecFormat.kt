package dev.geode.feature.studio.handlers

import dev.geode.engine.types.Millis
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.TrackId
import dev.geode.feature.studio.api.EditCut
import dev.geode.feature.studio.api.EditJob
import dev.geode.feature.studio.api.EditSource
import dev.geode.feature.studio.api.EditSources
import dev.geode.feature.studio.api.MadeFrom
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderExtras
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderLook
import dev.geode.feature.studio.api.RenderLoudness
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.core.NewRender
import dev.geode.feature.studio.handlers.edit.EditorProjectFormat
import org.json.JSONArray
import org.json.JSONObject

/**
 * A Render's choices as Studio's database keeps them beside its frozen look (#338): small, versioned
 * JSON (ADR-0004), so a queued Render resumes after the app is killed and a finished one can be made
 * again (#327). A value this version does not know falls back to the default rather than losing the
 * Render. An Edit's export (#318) keeps its cut here too: the clip editor's video and edit, or the
 * Timeline's whole project, with whether its videos were copied.
 */
internal object RenderSpecFormat {
    private const val CURRENT_VERSION = 1

    fun encode(render: NewRender): String {
        val format = render.format
        return JSONObject()
            .put(VERSION, CURRENT_VERSION)
            .put(TRACKS, JSONArray(render.tracks.map(::track)))
            .put(LOOK, look(render.look))
            .put(ASPECT, format.aspect.name)
            .put(SIZE, format.size.name)
            .put(FPS, format.fps)
            .put(QUALITY, format.quality.name)
            .put(CODEC, format.codec.name)
            .put(LENGTH, length(render.length))
            .put(PROJECT, render.project.value)
            .put(LOUDNESS, render.extras.loudness.name)
            .putOpt(MILKDROP, render.milkdrop)
            .putOpt(EDIT, render.edit?.let(::edit))
            .putOpt(MADE_FROM, render.madeFrom?.let(::madeFrom))
            .toString()
    }

    /** Throws [org.json.JSONException] when [text] is not a Render's choices at all. */
    fun decode(text: String): NewRender {
        val o = JSONObject(text)
        val defaults = RenderFormat()
        val format =
            RenderFormat(
                aspect = enumOr(o.optString(ASPECT), defaults.aspect),
                size = enumOr(o.optString(SIZE), defaults.size),
                fps = o.optInt(FPS, defaults.fps),
                quality = enumOr(o.optString(QUALITY), defaults.quality),
                codec = enumOr(o.optString(CODEC), defaults.codec),
            )
        val tracks = o.getJSONArray(TRACKS)
        return NewRender(
            tracks = List(tracks.length()) { trackOf(tracks.getJSONObject(it)) },
            look = lookOf(o.optJSONObject(LOOK)),
            format = format,
            length = lengthOf(o.optJSONObject(LENGTH)),
            project = ProjectId(o.getLong(PROJECT)),
            extras = RenderExtras(enumOr(o.optString(LOUDNESS), RenderLoudness.LEAVE_AS_IS)),
            milkdrop = if (o.has(MILKDROP)) o.getString(MILKDROP) else null,
            edit = o.optJSONObject(EDIT)?.let(::editOf),
            madeFrom = o.optJSONObject(MADE_FROM)?.let(::madeFromOf),
        )
    }

    private fun madeFrom(made: MadeFrom): JSONObject = JSONObject().put(FILE, made.file).put(KIND, made.kind.name)

    /** Null when the kind is not one this version knows: the video then lands as what made it says. */
    private fun madeFromOf(o: JSONObject): MadeFrom? {
        val kind = VideoKind.entries.firstOrNull { it.name == o.optString(KIND) } ?: return null
        return MadeFrom(o.getString(FILE), kind)
    }

    private fun edit(job: EditJob): JSONObject =
        JSONObject()
            .put(NAME, job.name)
            .put(SOURCES, job.sources.name)
            .put(CUT, cut(job.cut))
            .put(LOUDNESS, job.loudness.name)

    private fun editOf(o: JSONObject): EditJob =
        EditJob(
            o.optString(NAME),
            cutOf(o.getJSONObject(CUT)),
            enumOr(o.optString(SOURCES), EditSources.REFERENCE),
            enumOr(o.optString(LOUDNESS), RenderLoudness.LEAVE_AS_IS),
        )

    private fun cut(cut: EditCut): JSONObject =
        when (cut) {
            is EditCut.OneClip ->
                JSONObject()
                    .put(KIND, ONE_CLIP)
                    .put(SOURCE, source(cut.source))
                    .put(CLIP_EDIT, EditorProjectFormat.clipEdit(cut.edit))
            is EditCut.Cut -> JSONObject().put(KIND, TIMELINE).put(PROJECT, EditorProjectFormat.toObject(cut.project))
        }

    /** Throws [org.json.JSONException] or [IllegalArgumentException] when [o] is no cut this version reads. */
    private fun cutOf(o: JSONObject): EditCut =
        when (val kind = o.getString(KIND)) {
            ONE_CLIP -> EditCut.OneClip(sourceOf(o.getJSONObject(SOURCE)), EditorProjectFormat.clipEdit(o.getJSONObject(CLIP_EDIT)))
            TIMELINE -> EditCut.Cut(EditorProjectFormat.fromObject(o.getJSONObject(PROJECT)))
            else -> throw IllegalArgumentException("unknown cut: $kind")
        }

    private fun source(source: EditSource): JSONObject =
        JSONObject()
            .put(FILE, source.file)
            .put(NAME, source.name)
            .put(LENGTH_MS, source.length.value)
            .put(WIDTH, source.width)
            .put(HEIGHT, source.height)

    private fun sourceOf(o: JSONObject): EditSource =
        EditSource(o.getString(FILE), o.optString(NAME), Millis(o.optLong(LENGTH_MS)), o.optInt(WIDTH), o.optInt(HEIGHT))

    private fun track(track: RenderTrack): JSONObject =
        JSONObject().put(ID, track.id.value).put(TITLE, track.title).put(LENGTH_MS, track.length.value)

    private fun trackOf(o: JSONObject): RenderTrack =
        RenderTrack(TrackId(o.getString(ID)), o.optString(TITLE), Millis(o.optLong(LENGTH_MS)))

    private fun look(look: RenderLook): JSONObject =
        when (look) {
            RenderLook.Current -> JSONObject().put(KIND, CURRENT)
            is RenderLook.Preset -> JSONObject().put(KIND, PRESET).put(PRESET, look.preset.value)
        }

    private fun lookOf(o: JSONObject?): RenderLook =
        if (o?.optString(KIND) == PRESET) RenderLook.Preset(PresetId(o.getString(PRESET))) else RenderLook.Current

    private fun length(length: RenderLength): JSONObject =
        when (length) {
            RenderLength.WholeTrack -> JSONObject().put(KIND, WHOLE)
            is RenderLength.Section -> JSONObject().put(KIND, SECTION).put(START_MS, length.start.value).put(LENGTH_MS, length.length.value)
            RenderLength.Loop -> JSONObject().put(KIND, LOOP)
            is RenderLength.Minutes -> JSONObject().put(KIND, MINUTES).put(MINUTES, length.minutes)
        }

    private fun lengthOf(o: JSONObject?): RenderLength {
        if (o == null) return RenderLength.WholeTrack
        return when (o.optString(KIND)) {
            SECTION -> RenderLength.Section(Millis(o.getLong(START_MS)), Millis(o.getLong(LENGTH_MS)))
            LOOP -> RenderLength.Loop
            MINUTES -> RenderLength.Minutes(o.getInt(MINUTES))
            else -> RenderLength.WholeTrack
        }
    }

    private inline fun <reified T : Enum<T>> enumOr(
        name: String,
        fallback: T,
    ): T = enumValues<T>().firstOrNull { it.name == name } ?: fallback

    private const val VERSION = "version"
    private const val TRACKS = "tracks"
    private const val ID = "id"
    private const val TITLE = "title"
    private const val LENGTH_MS = "lengthMs"
    private const val START_MS = "startMs"
    private const val LOOK = "look"
    private const val KIND = "kind"
    private const val CURRENT = "current"
    private const val PRESET = "preset"
    private const val ASPECT = "aspect"
    private const val SIZE = "size"
    private const val FPS = "fps"
    private const val QUALITY = "quality"
    private const val CODEC = "codec"
    private const val LENGTH = "length"
    private const val WHOLE = "whole"
    private const val SECTION = "section"
    private const val LOOP = "loop"
    private const val MINUTES = "minutes"
    private const val PROJECT = "project"
    private const val LOUDNESS = "loudness"
    private const val MILKDROP = "milkdrop"
    private const val EDIT = "edit"
    private const val NAME = "name"
    private const val SOURCES = "sources"
    private const val CUT = "cut"
    private const val ONE_CLIP = "clip"
    private const val TIMELINE = "timeline"
    private const val SOURCE = "source"
    private const val CLIP_EDIT = "clipEdit"
    private const val FILE = "file"
    private const val WIDTH = "width"
    private const val HEIGHT = "height"
    private const val MADE_FROM = "madeFrom"
}
