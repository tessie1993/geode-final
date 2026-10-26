package dev.geode.feature.studio.handlers

import dev.geode.engine.types.Millis
import dev.geode.engine.types.PresetId
import dev.geode.engine.types.TrackId
import dev.geode.feature.studio.api.ProjectId
import dev.geode.feature.studio.api.RenderExtras
import dev.geode.feature.studio.api.RenderFormat
import dev.geode.feature.studio.api.RenderLength
import dev.geode.feature.studio.api.RenderLook
import dev.geode.feature.studio.api.RenderLoudness
import dev.geode.feature.studio.api.RenderTrack
import dev.geode.feature.studio.core.NewRender
import org.json.JSONArray
import org.json.JSONObject

/**
 * A Render's choices as Studio's database keeps them beside its frozen look (#338): small, versioned
 * JSON (ADR-0004), so a queued Render resumes after the app is killed and a finished one can be made
 * again (#327). A value this version does not know falls back to the default rather than losing the
 * Render.
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
        )
    }

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
        }

    private fun lengthOf(o: JSONObject?): RenderLength =
        if (o?.optString(KIND) == SECTION) {
            RenderLength.Section(Millis(o.getLong(START_MS)), Millis(o.getLong(LENGTH_MS)))
        } else {
            RenderLength.WholeTrack
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
    private const val PROJECT = "project"
    private const val LOUDNESS = "loudness"
}
