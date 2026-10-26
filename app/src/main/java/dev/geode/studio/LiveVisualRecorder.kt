package dev.geode.studio

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import dev.geode.RingLog
import dev.geode.engine.audio.VisualFeed
import dev.geode.engine.bridge.GeodeNative
import dev.geode.engine.types.Millis
import dev.geode.feature.settings.api.Settings
import dev.geode.feature.settings.api.SettingsState
import dev.geode.feature.studio.api.NewVideo
import dev.geode.feature.studio.api.VideoKind
import dev.geode.feature.studio.core.RecordingEnd
import dev.geode.feature.studio.core.RecordingProblem
import dev.geode.feature.studio.handlers.LiveRecorder
import dev.geode.render.RecordingTarget
import dev.geode.render.VisualizerRenderer
import dev.geode.util.bestEffort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.FileDescriptor
import java.io.IOException

/**
 * The live Visual's renderer while the Visual is bound to its sources (#317), for a Recording to draw
 * from. Set and cleared by the App UI with the Visual's bindings.
 */
object LiveVisualSource {
    @Volatile
    var renderer: VisualizerRenderer? = null
        private set

    fun bind(renderer: VisualizerRenderer) {
        this.renderer = renderer
    }

    /** Clears [renderer] if it is still the one bound. */
    fun unbind(renderer: VisualizerRenderer) {
        if (this.renderer === renderer) this.renderer = null
    }
}

/**
 * A Recording's tap on the Visual's audio feed (geode_feed_tap): the sound heard, in order and once,
 * at the point the Visual reads it, or silence while nothing is heard.
 */
internal class FeedTap(
    feed: VisualFeed,
) : AutoCloseable {
    private var handle = GeodeNative.feedTapCreate(feed.handle)
    private val rate = IntArray(1)

    val open: Boolean get() = handle != 0L

    /** The rate of the frames the last [take] gave. */
    val rateHz: Int get() = rate[0]

    /** Interleaved stereo frames into [out], at most half its size; the first take only marks the start. */
    fun take(out: FloatArray): Int = if (handle == 0L) 0 else GeodeNative.feedTapTake(handle, out, rate)

    override fun close() {
        if (handle != 0L) GeodeNative.feedTapDestroy(handle)
        handle = 0L
    }
}

/**
 * Records the live Visual on the phone (#317): Studio's [LiveRecorder]. The picture is the Visual
 * alone: the live renderer copies each frame it draws into the encoder's surface on its own context
 * ([VisualizerRenderer.recording]), so no App UI is ever in it. The sound is whatever drives the
 * Visual, the playing track or Live input, read from the engine's one audio feed ([feed]) at the
 * point the Visual reads it, so the two line up. The video is H.264 and AAC in an MP4, at the screen's
 * size when the encoder takes it, saved to `Movies/Geode` where Renders are saved; Studio then puts it
 * into the Project the person chooses.
 *
 * It stops when Studio asks, or when the Visual leaves the screen unless Settings' Recording keeps
 * going is on (#28); the Visual stops drawing while it is not seen, so the picture then holds.
 */
class LiveVisualRecorder(
    private val context: Context,
    private val settings: Settings,
    private val feed: () -> VisualFeed,
    private val liveVisualShown: () -> Boolean,
    private val work: CoroutineDispatcher,
) : LiveRecorder {
    override suspend fun record(
        song: String,
        started: () -> Unit,
        tick: (elapsed: Millis, freeBytes: Long) -> Unit,
        shouldStop: () -> Boolean,
    ): RecordingEnd =
        withContext(work) {
            val renderer = LiveVisualSource.renderer
            if (renderer == null || renderer.surfaceWidth <= 0 || renderer.surfaceHeight <= 0) {
                return@withContext RecordingEnd.Failed(RecordingProblem.NO_VISUAL)
            }
            val tap = FeedTap(feed())
            try {
                if (!tap.open) return@withContext RecordingEnd.Failed(RecordingProblem.NOT_SAVED)
                val pcm = FloatArray(TAP_FRAMES * 2)
                // The first take marks where the Recording's sound starts: its clock starts here.
                tap.take(pcm)
                val startNs = System.nanoTime()
                val rate = tap.rateHz.takeIf { it > 0 } ?: FALLBACK_RATE
                val file =
                    MovieFile.create(context, fileNameOf(song))
                        ?: return@withContext RecordingEnd.Failed(RecordingProblem.NOT_SAVED)
                val end = record(Take(renderer, tap, pcm, file, rate, startNs), started, tick, shouldStop)
                if (end is RecordingEnd.Saved) file.publish() else file.discard()
                if (end is RecordingEnd.Saved) end.copy(video = end.video.copy(name = nameOf(song), song = song)) else end
            } finally {
                tap.close()
            }
        }

    /** One Recording's parts: where it draws, hears and writes, and when it started. */
    private class Take(
        val renderer: VisualizerRenderer,
        val tap: FeedTap,
        val pcm: FloatArray,
        val file: MovieFile,
        val rate: Int,
        val startNs: Long,
    ) {
        // The sound at the Recording's one rate; its carried fraction belongs to this Recording alone.
        val matcher = RateMatcher(rate)
    }

    @Suppress("TooGenericExceptionCaught") // The encoders throw IllegalStateException and friends; the file is then not kept.
    private suspend fun record(
        take: Take,
        started: () -> Unit,
        tick: (elapsed: Millis, freeBytes: Long) -> Unit,
        shouldStop: () -> Boolean,
    ): RecordingEnd {
        val encoder =
            try {
                RecordingEncoder.open(take.file.descriptor, take.renderer.surfaceWidth, take.renderer.surfaceHeight, take.rate)
            } catch (refused: MediaCodec.CodecException) {
                RingLog.note(TAG, "the encoder refused the Recording", refused)
                null
            } catch (refused: Exception) {
                RingLog.note(TAG, "the encoders could not be made", refused)
                null
            } ?: return RecordingEnd.Failed(RecordingProblem.ENCODER_REFUSED)
        val target = RecordingTarget(encoder.surface)
        return try {
            encoder.startNs = take.startNs
            take.renderer.recording = target
            started()
            val elapsed = run(take, encoder, target, tick, shouldStop)
            letGo(take.renderer, target)
            hear(take, encoder)
            encoder.finish()
            when {
                target.failed && !encoder.hasPicture -> RecordingEnd.Failed(RecordingProblem.DRAWING_FAILED)
                !encoder.hasPicture -> RecordingEnd.Failed(RecordingProblem.NOTHING_RECORDED)
                else -> RecordingEnd.Saved(videoOf(take.file.uri, encoder, elapsed))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failed: Exception) {
            RingLog.note(TAG, "the Recording could not be finished", failed)
            RecordingEnd.Failed(RecordingProblem.NOT_SAVED)
        } finally {
            if (take.renderer.recording === target) take.renderer.recording = null
            encoder.release()
        }
    }

    /** Records until asked to stop, the drawing fails, or the Visual leaves the screen; returns how long it ran. */
    private suspend fun run(
        take: Take,
        encoder: RecordingEncoder,
        target: RecordingTarget,
        tick: (elapsed: Millis, freeBytes: Long) -> Unit,
        shouldStop: () -> Boolean,
    ): Millis {
        var ticked = 0L
        var hiddenSince = 0L
        while (true) {
            val elapsedMs = (System.nanoTime() - take.startNs) / NANOS_PER_MILLI
            if (shouldStop() || target.failed) return Millis(elapsedMs)
            if (liveVisualShown() || keepsGoing()) {
                hiddenSince = 0L
            } else if (hiddenSince == 0L) {
                hiddenSince = elapsedMs.coerceAtLeast(1L)
            } else if (elapsedMs - hiddenSince >= HIDDEN_GRACE_MS) {
                return Millis(elapsedMs)
            }
            hear(take, encoder)
            encoder.drain(false)
            if (elapsedMs - ticked >= TICK_MS) {
                ticked = elapsedMs
                tick(Millis(elapsedMs), freeBytes())
            }
            delay(POLL_MS)
        }
    }

    /** Hands the encoder the sound heard since last time, at the Recording's rate. */
    private fun hear(
        take: Take,
        encoder: RecordingEncoder,
    ) {
        while (true) {
            val frames = take.tap.take(take.pcm)
            if (frames <= 0) return
            val pcm = take.matcher.convert(take.pcm, frames, take.tap.rateHz)
            encoder.sound(pcm, pcm.size / 2)
            if (frames < TAP_FRAMES) return
        }
    }

    /** Takes the Recording's surface off the live renderer and waits, a little, until it lets go. */
    private suspend fun letGo(
        renderer: VisualizerRenderer,
        target: RecordingTarget,
    ) {
        if (renderer.recording === target) renderer.recording = null
        var waited = 0L
        // A Visual that is not drawing never lets go; the encoder is finished regardless.
        while (!target.released && !target.failed && waited < RELEASE_WAIT_MS) {
            delay(POLL_MS)
            waited += POLL_MS
        }
    }

    private fun keepsGoing(): Boolean = (settings.state.value as? SettingsState.Ready)?.values?.background?.recording == true

    private fun freeBytes(): Long =
        try {
            StatFs(Environment.getDataDirectory().path).availableBytes
        } catch (unreadable: IllegalArgumentException) {
            RingLog.note(TAG, "free storage could not be read", unreadable)
            Long.MAX_VALUE
        }

    private fun videoOf(
        uri: Uri,
        encoder: RecordingEncoder,
        elapsed: Millis,
    ): NewVideo =
        NewVideo(
            kind = VideoKind.RECORDING,
            name = "",
            file = uri.toString(),
            thumbnail = null,
            length = elapsed,
            width = encoder.width,
            height = encoder.height,
            song = "",
        )

    private companion object {
        const val TAG = "LiveVisualRecorder"

        /** The most frames one take from the feed gives: a quarter of a second at 48 kHz. */
        const val TAP_FRAMES = 12_000

        /** The rate a Recording's sound is made at when the feed has had none yet. */
        const val FALLBACK_RATE = 48_000

        const val POLL_MS = 10L
        const val TICK_MS = 1_000L
        const val RELEASE_WAIT_MS = 500L

        /** How long the Visual may be off screen before a Recording that does not keep going stops. */
        const val HIDDEN_GRACE_MS = 1_000L

        const val NANOS_PER_MILLI = 1_000_000L

        /** The longest a video's name grows from the song's title. */
        const val MAX_NAME = 80

        fun nameOf(song: String): String = song.trim().take(MAX_NAME).ifBlank { "Recording" }

        /** The video's file name: the song's title, without characters a file name cannot hold. */
        fun fileNameOf(song: String): String {
            val title =
                song
                    .replace(Regex("[\\\\/:*?\"<>|]"), " ")
                    .trim()
                    .take(MAX_NAME)
                    .ifBlank { "Recording" }
            return "$title (Geode Recording).mp4"
        }
    }
}

/**
 * A new video in the phone's Videos library under `Movies/Geode`, where Renders are saved, kept
 * pending (hidden) until [publish]; [discard] deletes it.
 */
internal class MovieFile private constructor(
    private val context: Context,
    val uri: Uri,
    private val pfd: ParcelFileDescriptor,
) {
    val descriptor: FileDescriptor get() = pfd.fileDescriptor

    fun publish() {
        bestEffort(TAG, "pfd.close()") { pfd.close() }
        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        context.contentResolver.update(uri, done, null, null)
    }

    fun discard() {
        bestEffort(TAG, "pfd.close()") { pfd.close() }
        bestEffort(TAG, "resolver.delete(uri)") { context.contentResolver.delete(uri, null, null) }
    }

    companion object {
        private const val TAG = "MovieFile"

        /** A pending video named [fileName], open for writing; null when the library would not take it. */
        fun create(
            context: Context,
            fileName: String,
        ): MovieFile? {
            val resolver = context.contentResolver
            val values =
                ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Geode")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            val uri =
                try {
                    resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                } catch (refused: IllegalArgumentException) {
                    RingLog.note(TAG, "the Videos library would not take the Recording", refused)
                    null
                } ?: return null
            val pfd =
                try {
                    resolver.openFileDescriptor(uri, "rw")
                } catch (unopened: IOException) {
                    RingLog.note(TAG, "the Recording's file could not be opened", unopened)
                    null
                }
            if (pfd == null) {
                bestEffort(TAG, "resolver.delete(uri)") { resolver.delete(uri, null, null) }
                return null
            }
            return MovieFile(context, uri, pfd)
        }
    }
}
