package dev.geode.studio.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dev.geode.audio.AiffPcm
import dev.geode.feature.studio.api.TrackJoin
import dev.geode.feature.studio.core.CrossfadeJoin
import dev.geode.feature.studio.core.Loudness
import dev.geode.util.bestEffort
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One continuous video's sound (#326): its tracks decoded one after another, each levelled by its own gain
 * (#329), brought to the first track's sample rate, and joined as the Playback settings join them, crossfaded,
 * gapless or parted by Playback's gap
 * ([CrossfadeJoin]), into one stereo AIFF file in the app's cache. The Render then reads that file as it
 * reads a Track: its analysis, the sound its scenes hear, and the sound muxed beside the video. Where each
 * track starts in it, and how long it overlaps the one before, come back with it for the chapters and the
 * Visual's moves. A track that cannot be decoded, or has no sound, fails the join.
 */
class PlaylistSound(
    private val context: Context,
) {
    /** A track to join: its sound, how long it is said to be, and the gain that levels it. */
    class Track(
        val uri: Uri,
        val expectedMs: Long,
        val gainDb: Double,
    )

    /** The joined sound: its [file], its rate, where each track starts and overlaps the one before, its length. */
    class Joined(
        val file: File,
        val sampleRate: Int,
        val startsMs: List<Long>,
        val overlapsMs: List<Long>,
        val lengthMs: Long,
    ) {
        val uri: Uri get() = Uri.fromFile(file)

        fun delete() {
            bestEffort(TAG, "file.delete()") { file.delete() }
        }
    }

    sealed interface Result {
        class Made(
            val joined: Joined,
        ) : Result

        data class Failed(
            val message: String,
        ) : Result

        data object Cancelled : Result
    }

    /** Joins [tracks] by [join], reporting [onProgress] (0..1) as it goes; blocking, so run it off the main thread. */
    @Suppress("TooGenericExceptionCaught") // Any decoder or file failure fails the join, and the file is removed.
    fun join(
        tracks: List<Track>,
        join: TrackJoin,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
    ): Result {
        val file = File.createTempFile("geode_playlist_", ".aiff", context.cacheDir)
        val result =
            try {
                RandomAccessFile(file, "rw").use { out -> Session(out, join).run(tracks, isCancelled, onProgress, file) }
            } catch (failed: Exception) {
                Result.Failed(failed.message ?: failed.javaClass.simpleName)
            }
        if (result !is Result.Made) bestEffort(TAG, "file.delete()") { file.delete() }
        return result
    }

    /** One join in progress: the file it writes, and the rate and crossfade it settles on with the first sound. */
    private inner class Session(
        private val out: RandomAccessFile,
        private val join: TrackJoin,
    ) {
        private var rate = 0
        private var joiner: CrossfadeJoin? = null
        private var bytes = ByteArray(0)

        fun run(
            tracks: List<Track>,
            isCancelled: () -> Boolean,
            onProgress: (Float) -> Unit,
            file: File,
        ): Result {
            out.write(ByteArray(AiffHeader.SIZE))
            for ((index, track) in tracks.withIndex()) {
                if (isCancelled()) return Result.Cancelled
                val heard =
                    sourceOf(track.uri).use { source ->
                        add(track, source, isCancelled) { onProgress((index + it) / tracks.size) }
                    }
                if (isCancelled()) return Result.Cancelled
                if (!heard) return Result.Failed("a track of the playlist has no sound: ${track.uri}")
            }
            val joined = checkNotNull(joiner) { "no track was joined" }
            joined.finish()
            if (joined.written > AiffHeader.maxFrames) return Result.Failed("the playlist is too long to join")
            out.seek(0)
            out.write(AiffHeader.bytes(CHANNELS, joined.written, rate))
            onProgress(1f)
            val ms = { frames: Long -> frames * MILLIS_PER_SECOND / rate }
            return Result.Made(Joined(file, rate, joined.starts.map(ms), joined.overlaps.map(ms), ms(joined.written)))
        }

        /** Joins [track]'s sound from [source]; false when it had none. */
        private fun add(
            track: Track,
            source: TrackSource,
            isCancelled: () -> Boolean,
            onTrackProgress: (Float) -> Unit,
        ): Boolean {
            val factor = Loudness.factorOf(track.gainDb).toFloat()
            var resampler: LinearResampler? = null
            var resampledFrom = 0
            var decodedMs = 0L
            var started = false
            while (!isCancelled()) {
                val block = source.next() ?: break
                val joined = joinerFor(block.rate)
                if (!started) joined.startTrack(track.expectedMs * rate / MILLIS_PER_SECOND)
                started = true
                for (i in 0 until block.frames * CHANNELS) block.samples[i] *= factor
                if (block.rate == rate) {
                    joined.write(block.samples, block.frames)
                } else {
                    // A track keeps its rate, but a decoder may say otherwise once it has started.
                    val converter =
                        resampler?.takeIf { resampledFrom == block.rate }
                            ?: LinearResampler(block.rate, rate, CHANNELS).also { resampler = it }
                    resampledFrom = block.rate
                    converter.process(block.samples, block.frames) { samples, frames -> joined.write(samples, frames) }
                }
                decodedMs += block.frames * MILLIS_PER_SECOND / block.rate
                if (track.expectedMs > 0) onTrackProgress((decodedMs.toFloat() / track.expectedMs).coerceIn(0f, 1f))
            }
            return started
        }

        /** The join, made with the first sound heard: at its rate, with the crossfade in its frames. */
        private fun joinerFor(firstRate: Int): CrossfadeJoin {
            joiner?.let { return it }
            rate = firstRate
            val fade = (join.crossfade.value * rate / MILLIS_PER_SECOND).toInt()
            val gap = (join.gap.value * rate / MILLIS_PER_SECOND).toInt()
            return CrossfadeJoin(fade, CHANNELS, join.curve, gapFrames = gap, out = ::write).also { joiner = it }
        }

        /** Writes [frames] of joined stereo sound as 16-bit big-endian samples, as AIFF keeps them. */
        private fun write(
            samples: FloatArray,
            frames: Int,
        ) {
            val count = frames * CHANNELS
            if (bytes.size < count * 2) bytes = ByteArray(count * 2)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            for (i in 0 until count) buffer.putShort((samples[i].coerceIn(-1f, 1f) * SHORT_MAX).toInt().toShort())
            out.write(bytes, 0, count * 2)
        }
    }

    /** [uri]'s sound: read directly when it is AIFF, as the rest of the Render reads it, or decoded. */
    private fun sourceOf(uri: Uri): TrackSource = AiffPcm.open(context, uri)?.let(::AiffSource) ?: CodecSource.open(context, uri)

    /** A block of a track's sound: [frames] of interleaved stereo at [rate] Hz, in an array reused for the next. */
    private class Block(
        val samples: FloatArray,
        val frames: Int,
        val rate: Int,
    )

    /** Where a track's sound comes from. */
    private interface TrackSource : Closeable {
        /** The next block, or null at the end. */
        fun next(): Block?
    }

    private class AiffSource(
        private val aiff: AiffPcm,
    ) : TrackSource {
        private val shorts = ShortArray(BLOCK_SAMPLES - BLOCK_SAMPLES % aiff.channels)
        private var stereo = FloatArray(0)

        override fun next(): Block? {
            val count = aiff.read(shorts)
            if (count <= 0) return null
            val frames = count / aiff.channels
            stereo = stereoOf(frames, aiff.channels, stereo) { shorts[it] / SHORT_SCALE }
            return Block(stereo, frames, aiff.sampleRate)
        }

        override fun close() = aiff.close()
    }

    private class CodecSource private constructor(
        private val extractor: MediaExtractor,
        private val codec: MediaCodec,
    ) : TrackSource {
        private val info = MediaCodec.BufferInfo()
        private var inputDone = false
        private var outputDone = false
        private var decoded = FloatArray(0)
        private var stereo = FloatArray(0)

        override fun next(): Block? {
            var idle = 0
            while (!outputDone && idle < MAX_IDLE_POLLS) {
                if (!inputDone) feed()
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index < 0) {
                    idle++
                    continue
                }
                idle = 0
                val block = if (info.size > 0) blockAt(index) else null
                codec.releaseOutputBuffer(index, false)
                outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (block != null) return block
            }
            check(outputDone) { "the track's decoder stalled" }
            return null
        }

        private fun feed() {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0) return
            val buffer = checkNotNull(codec.getInputBuffer(index)) { "decoder input buffer null (codec error state)" }
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) {
                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
            } else {
                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                extractor.advance()
            }
        }

        private fun blockAt(index: Int): Block? {
            val format = codec.outputFormat
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            if (channels <= 0 || rate <= 0) return null
            val buffer = checkNotNull(codec.getOutputBuffer(index)) { "decoder output buffer null (codec error state)" }
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val encoding =
                if (format.containsKey(
                        MediaFormat.KEY_PCM_ENCODING,
                    )
                ) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
            val count: Int
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
                count = floats.remaining()
                if (decoded.size < count) decoded = FloatArray(count)
                floats.get(decoded, 0, count)
            } else {
                val shorts = buffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                count = shorts.remaining()
                if (decoded.size < count) decoded = FloatArray(count)
                for (i in 0 until count) decoded[i] = shorts.get(i) / SHORT_SCALE
            }
            val frames = count / channels
            stereo = stereoOf(frames, channels, stereo) { decoded[it] }
            return Block(stereo, frames, rate)
        }

        override fun close() {
            bestEffort(TAG, "codec.stop()") { codec.stop() }
            bestEffort(TAG, "codec.release()") { codec.release() }
            bestEffort(TAG, "extractor.release()") { extractor.release() }
        }

        companion object {
            @Suppress("TooGenericExceptionCaught") // Whatever fails, the extractor and decoder are released first.
            fun open(
                context: Context,
                uri: Uri,
            ): CodecSource {
                val extractor = MediaExtractor()
                var codec: MediaCodec? = null
                try {
                    extractor.setDataSource(context, uri, null)
                    val track =
                        (0 until extractor.trackCount).firstOrNull { index ->
                            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                        } ?: throw IllegalArgumentException("No audio track in $uri")
                    val format = extractor.getTrackFormat(track)
                    extractor.selectTrack(track)
                    val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
                    codec = decoder
                    decoder.configure(format, null, null, 0)
                    decoder.start()
                    return CodecSource(extractor, decoder)
                } catch (failed: Exception) {
                    codec?.let { bestEffort(TAG, "codec.release()") { it.release() } }
                    extractor.release()
                    throw failed
                }
            }
        }
    }

    private companion object {
        const val TAG = "PlaylistSound"
        const val CHANNELS = 2
        const val MILLIS_PER_SECOND = 1_000L
        const val SHORT_SCALE = 32768f
        const val SHORT_MAX = 32767f
        const val BLOCK_SAMPLES = 16_384
        const val TIMEOUT_US = 10_000L

        /** Empty polls in a row after which a decoder that says nothing more is taken to have stalled. */
        const val MAX_IDLE_POLLS = 500

        /**
         * [frames] of [channels]-channel sound, read sample by sample from [sample], as interleaved stereo in
         * [into] (grown when too small): a mono track is heard on both sides, and a track of more channels by
         * its first two.
         */
        inline fun stereoOf(
            frames: Int,
            channels: Int,
            into: FloatArray,
            sample: (Int) -> Float,
        ): FloatArray {
            val out = if (into.size < frames * CHANNELS) FloatArray(frames * CHANNELS) else into
            for (f in 0 until frames) {
                val left = sample(f * channels)
                out[f * CHANNELS] = left
                out[f * CHANNELS + 1] = if (channels > 1) sample(f * channels + 1) else left
            }
            return out
        }
    }
}
