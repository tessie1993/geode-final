package dev.geode.studio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import dev.geode.util.bestEffort
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The encoders and the muxer of one Recording of the live Visual (#317): H.264 from the [surface] the
 * live renderer copies its frames into, and AAC from the feed's PCM, muxed into one MP4.
 *
 * Every time is on [System.nanoTime]'s clock, from [startNs] on: the live renderer stamps each frame
 * when it was drawn, and the sound is counted in frames from the moment the feed's tap started, so
 * picture and sound line up as they were seen and heard. Frames drawn before [startNs] are dropped.
 * One thread drives it.
 */
internal class RecordingEncoder private constructor(
    private val video: MediaCodec,
    private val audio: MediaCodec,
    private val muxer: MediaMuxer,
    val surface: Surface,
    val width: Int,
    val height: Int,
    private val audioRate: Int,
) {
    /** When the Recording starts; set before the first frame or sound is handed over. */
    var startNs: Long = 0L

    private val info = MediaCodec.BufferInfo()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxing = false
    private var videoEnded = false
    private var audioEnded = false
    private var audioFrames = 0L
    private var lastVideoUs = -1L
    private var lastAudioUs = -1L

    // Samples that came before both tracks were known, and so before the muxer could start.
    private val waiting = mutableListOf<Waiting>()

    /** Whether any picture reached the file: muxed, not only encoded. */
    var hasPicture: Boolean = false
        private set

    /** Encodes [frames] interleaved stereo frames of [pcm], at the Recording's rate, after the sound before. */
    fun sound(
        pcm: FloatArray,
        frames: Int,
    ) {
        var done = 0
        var tries = 0
        while (done < frames && tries < MAX_INPUT_TRIES) {
            val index = audio.dequeueInputBuffer(INPUT_WAIT_US)
            if (index < 0) {
                tries++
                drain(false)
                continue
            }
            val buffer = audio.getInputBuffer(index) ?: return
            buffer.clear()
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val n = minOf(frames - done, buffer.remaining() / BYTES_PER_FRAME)
            for (i in 0 until n) {
                buffer.putShort(RecordingMath.pcm16(pcm[(done + i) * 2]))
                buffer.putShort(RecordingMath.pcm16(pcm[(done + i) * 2 + 1]))
            }
            audio.queueInputBuffer(index, 0, n * BYTES_PER_FRAME, audioUs(), 0)
            audioFrames += n
            done += n
        }
    }

    /** Hands what the encoders made to the muxer; with [ending], until both have finished. */
    fun drain(ending: Boolean) {
        drain(video, isVideo = true, ending = ending)
        drain(audio, isVideo = false, ending = ending)
    }

    /**
     * Finishes the file: the picture ends where it is, the sound after the last frame handed over.
     * The live renderer must no longer draw into [surface].
     */
    fun finish() {
        video.signalEndOfInputStream()
        val index = audio.dequeueInputBuffer(END_WAIT_US)
        if (index >= 0) audio.queueInputBuffer(index, 0, 0, audioUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM) else audioEnded = true
        drain(true)
        if (muxing) muxer.stop()
        muxing = false
    }

    /** Lets everything go; safe after a failure, and more than once. */
    fun release() {
        bestEffort(TAG, "video.release()") { video.release() }
        bestEffort(TAG, "audio.release()") { audio.release() }
        bestEffort(TAG, "surface.release()") { surface.release() }
        bestEffort(TAG, "muxer.release()") { muxer.release() }
    }

    private fun audioUs(): Long = audioFrames * MICROS_PER_SECOND / audioRate

    private fun drain(
        codec: MediaCodec,
        isVideo: Boolean,
        ending: Boolean,
    ) {
        var idle = 0
        while (!(if (isVideo) videoEnded else audioEnded)) {
            val index = codec.dequeueOutputBuffer(info, if (ending) END_WAIT_US else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!ending || ++idle > MAX_END_WAITS) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> track(codec.outputFormat, isVideo)
                index >= 0 -> take(codec, index, isVideo)
            }
        }
    }

    /** Writes the encoded buffer at [index] and gives it back; the end of the stream marks that track ended. */
    private fun take(
        codec: MediaCodec,
        index: Int,
        isVideo: Boolean,
    ) {
        val buffer = codec.getOutputBuffer(index)
        if (buffer != null) write(buffer, isVideo)
        codec.releaseOutputBuffer(index, false)
        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) return
        if (isVideo) videoEnded = true else audioEnded = true
    }

    private fun track(
        format: MediaFormat,
        isVideo: Boolean,
    ) {
        if (muxing) return
        if (isVideo) videoTrack = muxer.addTrack(format) else audioTrack = muxer.addTrack(format)
        if (videoTrack < 0 || audioTrack < 0) return
        muxer.start()
        muxing = true
        waiting.forEach { muxer.writeSampleData(if (it.isVideo) videoTrack else audioTrack, it.data, it.info) }
        if (waiting.any { it.isVideo }) hasPicture = true
        waiting.clear()
    }

    private fun write(
        buffer: ByteBuffer,
        isVideo: Boolean,
    ) {
        if (info.size <= 0 || info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        val us = if (isVideo) info.presentationTimeUs - startNs / NANOS_PER_MICRO else info.presentationTimeUs
        // Frames drawn before the start are not the Recording's; times only move forward in a track.
        if (us < 0 || us <= (if (isVideo) lastVideoUs else lastAudioUs)) return
        if (isVideo) lastVideoUs = us else lastAudioUs = us
        val sample = MediaCodec.BufferInfo().apply { set(0, info.size, us, info.flags) }
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        if (muxing) {
            muxer.writeSampleData(if (isVideo) videoTrack else audioTrack, buffer, sample)
            if (isVideo) hasPicture = true
        } else if (waiting.size < MAX_WAITING) {
            val copy = ByteBuffer.allocateDirect(info.size).put(buffer)
            copy.flip()
            waiting += Waiting(isVideo, copy, sample)
        }
    }

    private class Waiting(
        val isVideo: Boolean,
        val data: ByteBuffer,
        val info: MediaCodec.BufferInfo,
    )

    companion object {
        private const val TAG = "RecordingEncoder"
        private const val CHANNELS = 2
        private const val BYTES_PER_FRAME = 4
        private const val AUDIO_BIT_RATE = 192_000
        private const val MICROS_PER_SECOND = 1_000_000L
        private const val NANOS_PER_MICRO = 1_000L
        private const val INPUT_WAIT_US = 5_000L
        private const val END_WAIT_US = 10_000L
        private const val MAX_INPUT_TRIES = 20
        private const val MAX_END_WAITS = 300
        private const val MAX_WAITING = 256

        /**
         * The encoders for a Recording of the Visual drawn at [screenWidth] x [screenHeight], writing to
         * [file]; null when the phone's encoder takes no size from the screen's down to half.
         * Throws when an encoder cannot be made at all.
         */
        @Suppress("TooGenericExceptionCaught") // Whatever went wrong, what was made is let go before it is passed on.
        fun open(
            file: FileDescriptor,
            screenWidth: Int,
            screenHeight: Int,
            audioRate: Int,
        ): RecordingEncoder? {
            val video = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            var audio: MediaCodec? = null
            var muxer: MediaMuxer? = null
            try {
                // A video encoder always reports video capabilities; one that does not cannot record.
                val capabilities =
                    video.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities ?: run {
                        video.release()
                        return null
                    }
                val alignWidth = capabilities.widthAlignment
                val alignHeight = capabilities.heightAlignment
                val size = RecordingMath.sizeFor(screenWidth, screenHeight, alignWidth, alignHeight, capabilities::isSizeSupported)
                if (size == null) {
                    video.release()
                    return null
                }
                val (width, height) = size
                video.configure(videoFormat(width, height), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = video.createInputSurface()
                audio = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                audio.configure(audioFormat(audioRate), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                muxer = MediaMuxer(file, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                video.start()
                audio.start()
                return RecordingEncoder(video, audio, muxer, surface, width, height, audioRate)
            } catch (failed: Exception) {
                bestEffort(TAG, "video.release()") { video.release() }
                audio?.let { bestEffort(TAG, "audio.release()") { it.release() } }
                muxer?.let { bestEffort(TAG, "muxer.release()") { it.release() } }
                throw failed
            }
        }

        private fun videoFormat(
            width: Int,
            height: Int,
        ): MediaFormat =
            MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, RecordingMath.bitRateOf(width, height))
                setInteger(MediaFormat.KEY_FRAME_RATE, RecordingMath.FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

        private fun audioFormat(rate: Int): MediaFormat =
            MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, CHANNELS).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
            }
    }
}
