package dev.geode.feature.visuals.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Preset share links, as Geode has always made them: `geode://preset/` followed by the preset file's
 * text, gzipped and written in URL-safe Base64 without padding. The app stays offline; a link travels
 * through the share sheet and comes back pasted or shared into Geode.
 *
 * A link carries the preset file exactly, whatever its version: one an older Geode shared holds the
 * old format, which is upgraded when it is saved.
 */
object PresetLinks {
    const val SCHEME = "geode"
    const val HOST = "preset"

    /** The longest link Geode makes or reads; a preset too big for one is shared as its file. */
    const val MAX_LINK_LENGTH = 8_000

    /** The most text a link may unpack to, so a hostile link cannot fill the memory. */
    const val MAX_TEXT_BYTES = 4 * 1024 * 1024

    private const val PREFIX = "$SCHEME://$HOST/"

    /** [text] as a link; it may be longer than [MAX_LINK_LENGTH], which [fits] tells. */
    fun encode(text: String): String {
        val packed = ByteArrayOutputStream()
        GZIPOutputStream(packed).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(packed.toByteArray())
    }

    /** Whether [link] is short enough to share as a link. */
    fun fits(link: String): Boolean = link.length <= MAX_LINK_LENGTH

    /** The first preset link in [text] (a message, say), up to the next space; null when there is none. */
    fun findIn(text: String): String? {
        val at = text.indexOf(PREFIX, ignoreCase = true)
        if (at < 0) return null
        val end = (at until text.length).firstOrNull { text[it].isWhitespace() } ?: text.length
        return text.substring(at, end)
    }

    /**
     * The preset text [link] carries, or null when it is not a preset link, is longer than
     * [MAX_LINK_LENGTH], does not unpack, unpacks to more than [MAX_TEXT_BYTES] or to nothing.
     */
    @Suppress("SwallowedException")
    fun decode(link: String): String? {
        val trimmed = link.trim()
        if (!trimmed.startsWith(PREFIX, ignoreCase = true) || !fits(trimmed)) return null
        val payload = trimmed.substring(PREFIX.length).substringBefore('#').substringBefore('?')
        val bytes =
            try {
                GZIPInputStream(Base64.getUrlDecoder().decode(payload).inputStream()).use { it.readNBytes(MAX_TEXT_BYTES + 1) }
            } catch (_: IOException) {
                return null
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (bytes.size > MAX_TEXT_BYTES) return null
        return bytes.toString(Charsets.UTF_8).ifBlank { null }
    }
}
