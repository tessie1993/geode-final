package dev.geode.feature.studio.handlers.edit

import dev.geode.feature.studio.api.RenderAspect
import dev.geode.feature.studio.api.RenderSize
import dev.geode.feature.studio.api.edit.ClipLook
import org.json.JSONArray
import org.json.JSONObject

/**
 * The importer for the old editor's projects (#347): `files/editor` version 1, as the legacy app wrote
 * it. It is the one place that knows the legacy enums a clip's edit was saved with (the old export's
 * ratio, quality and look); everything else reads [EditorProjectFormat]'s own version only. A project is
 * brought up to the current version step by step (ADR-0004), then read as any other.
 */
object OldEditorProjects {
    /** The old editor's version. */
    const val VERSION = 1

    /** The old export's ratios (`ExportRatio`), by the names it saved, as Studio's shapes. */
    private val RATIOS: Map<String, RenderAspect> =
        mapOf(
            "R16_9" to RenderAspect.R16_9,
            "R9_16" to RenderAspect.R9_16,
            "R1_1" to RenderAspect.R1_1,
            "R4_5" to RenderAspect.R4_5,
            "R4_3" to RenderAspect.R4_3,
            "R21_9" to RenderAspect.R21_9,
        )

    /** The old export's qualities (`ExportQuality`), by the names it saved, as Studio's sizes. */
    private val QUALITIES: Map<String, RenderSize> =
        mapOf(
            "HD720" to RenderSize.HD720,
            "FHD1080" to RenderSize.FHD1080,
            "UHD4K" to RenderSize.UHD4K,
        )

    /** The old clip editor's looks (`export.ClipLook`), by the names it saved. */
    private val LOOKS: Map<String, ClipLook> =
        mapOf(
            "NONE" to ClipLook.NONE,
            "PUNCH" to ClipLook.PUNCH,
            "BLEACH" to ClipLook.BLEACH,
            "NEON" to ClipLook.NEON,
            "NIGHT" to ClipLook.NIGHT,
            "MONO" to ClipLook.MONO,
            "INVERT" to ClipLook.INVERT,
        )

    /**
     * [old], a project of an older version, as the current version: each video clip's edit gets Studio's
     * names for its shape, size and look. A legacy name this version has no match for is dropped, and
     * the reader's default stands (as shot, 1080p, no look). [old] itself is left untouched.
     */
    fun upgrade(old: JSONObject): JSONObject {
        val project = JSONObject(old.toString())
        project.optJSONObject("timeline")?.optJSONArray("lanes").objects().forEach { lane ->
            lane.optJSONArray("clips").objects().forEach { clip ->
                clip.optJSONObject("content")?.optJSONObject("edit")?.let(::upgradeEdit)
            }
        }
        return project.put("version", EditorProjectFormat.VERSION)
    }

    private fun upgradeEdit(edit: JSONObject) {
        rename(edit, "ratio") { RATIOS[it]?.name }
        rename(edit, "quality") { QUALITIES[it]?.name }
        rename(edit, "look") { LOOKS[it]?.name }
    }

    /** [key]'s legacy name in [edit] replaced with [current]'s, or removed when it has none. */
    private fun rename(
        edit: JSONObject,
        key: String,
        current: (String) -> String?,
    ) {
        if (!edit.has(key) || edit.isNull(key)) return
        val renamed = current(edit.getString(key))
        if (renamed == null) edit.remove(key) else edit.put(key, renamed)
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
}
