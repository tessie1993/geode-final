package dev.geode.feature.studio.api

import dev.geode.feature.studio.api.edit.ClipEdit

/**
 * What a Short is (#328, owner decision on #25): a 9:16 cut of a Render or Recording, under a minute, at
 * the TikTok / Shorts preset ([RenderPreset.TIKTOK_AND_SHORTS]: 1080p, 30 fps, -14 LUFS).
 */
object Shorts {
    /** A Short is under a minute: a section this long or longer cannot be chosen. */
    const val LIMIT_MS: Long = 60_000L

    /** The section a new Short starts with: the source's opening, up to this long. */
    const val FIRST_SECTION_MS: Long = 59_000L

    /** The shortest section a Short may have. */
    const val SHORTEST_MS: Long = 1_000L

    /** Where the crop sits until it is moved. */
    const val CENTRE: Float = ClipEdit.CENTRED

    /** The preset every Short is made at. */
    val PRESET: RenderPreset = RenderPreset.TIKTOK_AND_SHORTS

    /** Whether [startMs]..[endMs] is a section a Short of a source [lengthMs] long may have. */
    fun fits(
        startMs: Long,
        endMs: Long,
        lengthMs: Long,
    ): Boolean {
        val inSource = startMs >= 0 && endMs <= lengthMs
        val span = endMs - startMs
        return inSource && span >= SHORTEST_MS && span < LIMIT_MS
    }
}
