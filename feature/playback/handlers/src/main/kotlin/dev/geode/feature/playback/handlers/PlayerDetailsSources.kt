package dev.geode.feature.playback.handlers

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dev.geode.engine.types.TrackId
import dev.geode.feature.playback.api.LyricsSource
import java.io.File

/** A track's lyrics as found, still to be read: the [text] and where it came from. */
data class LyricsText(
    val text: String,
    val source: LyricsSource,
)

/** Where Playback finds a track's lyrics (#147). Nothing is fetched: the app has no network access. */
interface LyricsFiles {
    /**
     * Every lyrics text found for [track], best first; the first that holds any words is used. Empty
     * when it has none. May throw when nothing could be read.
     */
    suspend fun find(track: TrackId): List<LyricsText>

    companion object {
        /** Finds no lyrics, for a store built without a source. */
        val NONE: LyricsFiles =
            object : LyricsFiles {
                override suspend fun find(track: TrackId): List<LyricsText> = emptyList()
            }
    }
}

/**
 * Where Playback finds a track's loudness for its seek waveform (#147): one level per moment, from the
 * track's start to its end, as the whole-song analysis measured it (ADR-0001: the live Visual never
 * uses whole-song analysis; the waveform is App UI). `:app` gives it the analysis cache when it wires
 * Playback in (#17).
 */
interface TrackLoudness {
    /** [track]'s loudness levels, or null when the track has not been analysed. May throw. */
    suspend fun levels(track: TrackId): FloatArray?

    companion object {
        /** Knows no loudness, for a store built without a source. */
        val NONE: TrackLoudness =
            object : TrackLoudness {
                override suspend fun levels(track: TrackId): FloatArray? = null
            }
    }
}

/**
 * Lyrics where the old player found them: an `.lrc` file with the track's name next to the track,
 * then the words written into the track's own tags. A place that cannot be read is skipped. Called
 * on the handlers' background dispatcher.
 */
class AndroidLyricsFiles(
    private val context: Context,
) : LyricsFiles {
    override suspend fun find(track: TrackId): List<LyricsText> {
        val uri = Uri.parse(track.value)
        return listOfNotNull(
            runCatching { beside(uri) }.getOrNull()?.let { LyricsText(it, LyricsSource.LRC_FILE) },
            runCatching { tags(uri) }.getOrNull()?.let { LyricsText(it, LyricsSource.TRACK_TAGS) },
        )
    }

    /** The text of the `.lrc` file next to the track, if there is one. */
    private fun beside(track: Uri): String? =
        when (track.scheme) {
            "file" -> track.path?.let { path -> File(path.substringBeforeLast('.', path) + LRC).takeIf { it.isFile }?.readText() }
            "content" -> besideDocument(track)
            else -> null
        }

    private fun besideDocument(track: Uri): String? {
        val document = DocumentFile.fromSingleUri(context, track) ?: return null
        val name = document.name ?: return null
        val wanted = name.substringBeforeLast('.', name) + LRC
        val lrc = document.parentFile?.listFiles()?.firstOrNull { it.name.equals(wanted, ignoreCase = true) } ?: return null
        return context.contentResolver.openInputStream(lrc.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** The words in the track's own tags, if any. */
    private fun tags(track: Uri): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, track)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_WRITER)?.takeIf { it.isNotBlank() }
        } finally {
            retriever.release()
        }
    }

    private companion object {
        const val LRC = ".lrc"
    }
}
