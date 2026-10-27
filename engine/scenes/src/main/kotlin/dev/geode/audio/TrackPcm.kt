package dev.geode.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dev.geode.render.offscreen.FramePcm
import dev.geode.render.offscreen.PcmSpan
import dev.geode.util.bestEffort
import java.io.Closeable
import java.nio.ByteOrder

/**
 * A track's sound decoded in step with a Render's frames (#342): each frame asks for the window that ends
 * at the moment it shows, and the decoder reads on just far enough to hold it, so a whole track never sits
 * in memory and projectM, the shader PCM row and every family's PCM strike hear the real audio. Frames
 * must ask in order, as a Render draws them. A track that cannot be decoded gives silence, logged once.
 * Not thread-safe; it lives on the Render's own thread.
 */
class TrackPcm(
    private val context: Context,
    private val uri: Uri,
) : FramePcm,
    Closeable {
    private val window = PcmWindow(RING_FRAMES)
    private var source: Source? = null
    private var opened = false
    private var ended = false
    private var sampleRate = 0
    private var lastEnd = -1L

    override fun spanAt(
        timeUs: Long,
        out: FloatArray,
    ): PcmSpan {
        val reader = open() ?: return PcmSpan.NONE
        while (!ended && (sampleRate == 0 || window.written < endFrame(timeUs))) {
            if (!reader.next(window) { sampleRate = it }) ended = true
        }
        if (sampleRate == 0) return PcmSpan.NONE
        val end = endFrame(timeUs)
        return window.span(end, lastEnd, out, WINDOW_FRAMES).also { lastEnd = end }
    }

    override fun close() {
        source?.let { bestEffort(TAG, "source.close()") { it.close() } }
        source = null
    }

    private fun endFrame(timeUs: Long): Long = timeUs * sampleRate / MICROS_PER_SECOND

    @Suppress("TooGenericExceptionCaught") // Any decoder failure is silence, never a failed Render.
    private fun open(): Source? {
        if (!opened) {
            opened = true
            source =
                try {
                    AiffPcm.open(context, uri)?.let(::AiffSource) ?: CodecSource.open(context, uri)
                } catch (failed: Exception) {
                    android.util.Log.w(TAG, "the track's sound could not be decoded for the Render", failed)
                    null
                }
        }
        return source
    }

    /** Where the decoded sound comes from. */
    private interface Source : Closeable {
        /** Decodes the next block into [into], telling [rate] the sample rate first; false at the end. */
        fun next(
            into: PcmWindow,
            rate: (Int) -> Unit,
        ): Boolean
    }

    private class AiffSource(
        private val aiff: AiffPcm,
    ) : Source {
        private val shorts = ShortArray(BLOCK_SAMPLES)
        private val floats = FloatArray(BLOCK_SAMPLES)

        override fun next(
            into: PcmWindow,
            rate: (Int) -> Unit,
        ): Boolean {
            rate(aiff.sampleRate)
            val n = aiff.read(shorts)
            if (n <= 0) return false
            for (i in 0 until n) floats[i] = shorts[i] / SHORT_SCALE
            into.write(floats, n / aiff.channels, aiff.channels)
            return true
        }

        override fun close() = aiff.close()
    }

    private class CodecSource private constructor(
        private val extractor: MediaExtractor,
        private val codec: MediaCodec,
    ) : Source {
        private val info = MediaCodec.BufferInfo()
        private var inputDone = false
        private var outputDone = false
        private var scratch = FloatArray(BLOCK_SAMPLES)

        /** Decodes until a block of sound comes out; false once the stream has ended or the decoder stalls. */
        override fun next(
            into: PcmWindow,
            rate: (Int) -> Unit,
        ): Boolean {
            var idle = 0
            while (!outputDone && idle < MAX_IDLE_POLLS) {
                if (!inputDone) feed()
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index < 0) {
                    idle++
                    continue
                }
                idle = 0
                val wrote = info.size > 0
                if (wrote) write(index, into, rate)
                codec.releaseOutputBuffer(index, false)
                outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (wrote) return true
            }
            return false
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

        private fun write(
            index: Int,
            into: PcmWindow,
            rate: (Int) -> Unit,
        ) {
            val format = codec.outputFormat
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            rate(format.getInteger(MediaFormat.KEY_SAMPLE_RATE))
            val buffer = checkNotNull(codec.getOutputBuffer(index)) { "decoder output buffer null (codec error state)" }
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            val encoding =
                if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                } else {
                    AudioFormat.ENCODING_PCM_16BIT
                }
            val count: Int
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
                count = floats.remaining()
                if (scratch.size < count) scratch = FloatArray(count)
                floats.get(scratch, 0, count)
            } else {
                val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                count = shorts.remaining()
                if (scratch.size < count) scratch = FloatArray(count)
                for (i in 0 until count) scratch[i] = shorts.get(i) / SHORT_SCALE
            }
            if (channels > 0) into.write(scratch, count / channels, channels)
        }

        override fun close() {
            bestEffort(TAG, "codec.stop()") { codec.stop() }
            bestEffort(TAG, "codec.release()") { codec.release() }
            bestEffort(TAG, "extractor.release()") { extractor.release() }
        }

        companion object {
            @Suppress("TooGenericExceptionCaught") // Whatever fails, the extractor is released before it goes on up.
            fun open(
                context: Context,
                uri: Uri,
            ): CodecSource {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, uri, null)
                    val track =
                        (0 until extractor.trackCount).firstOrNull { index ->
                            extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                        } ?: throw IllegalArgumentException("No audio track in file")
                    val format = extractor.getTrackFormat(track)
                    extractor.selectTrack(track)
                    return CodecSource(extractor, startedDecoder(format))
                } catch (failed: Exception) {
                    extractor.release()
                    throw failed
                }
            }

            /** A running decoder for [format]; released again if it will not start. */
            private fun startedDecoder(format: MediaFormat): MediaCodec {
                val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
                try {
                    codec.configure(format, null, null, 0)
                    codec.start()
                } catch (failed: IllegalStateException) {
                    codec.release()
                    throw failed
                }
                return codec
            }
        }
    }

    private companion object {
        const val TAG = "TrackPcm"
        const val MICROS_PER_SECOND = 1_000_000L
        const val TIMEOUT_US = 10_000L
        const val SHORT_SCALE = 32768f
        const val BLOCK_SAMPLES = 16_384

        /** Empty polls in a row after which a decoder that says nothing more is taken to have stalled. */
        const val MAX_IDLE_POLLS = 500

        /** The window every scene reads: projectM's 576-frame ring, the window MilkDrop measures. */
        const val WINDOW_FRAMES = 576

        /** Two seconds at 48 kHz: far more than a span, so a frame never asks for sound already dropped. */
        const val RING_FRAMES = 96_000
    }
}
