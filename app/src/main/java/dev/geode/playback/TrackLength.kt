package dev.geode.playback

import android.content.Context
import android.media.MediaMetadataRetriever
import dev.geode.engine.types.Millis
import dev.geode.engine.types.TrackId

/**
 * How long a song is, from what the file says about itself: the length its container records, read
 * by [MediaMetadataRetriever]. This is the file's metadata, never an analysis of its sound (#17).
 *
 * For songs the phone's media index did not give a length: ones opened from another app, handed over
 * by a car, or kept by the old player. A song whose length cannot be read stays at zero, which
 * Playback takes as "not known yet". Reads the file: never on the main thread.
 */
object TrackLength {
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // A file that cannot be read has no known length.
    fun of(
        context: Context,
        track: TrackId,
    ): Millis =
        try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, track.toUri())
                retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?.let(::Millis) ?: Millis.ZERO
            } finally {
                retriever.release()
            }
        } catch (unreadable: Exception) {
            Millis.ZERO
        }

    /** [known] when it is a length, otherwise what the file says. */
    fun orRead(
        context: Context,
        track: TrackId,
        known: Millis,
    ): Millis = if (known > Millis.ZERO) known else of(context, track)
}
