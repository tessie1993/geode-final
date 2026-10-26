package dev.geode.export

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import dev.geode.analysis.FeatureTimeline
import dev.geode.audio.TrackPcm
import dev.geode.engine.bridge.CustomizationSnapshot
import dev.geode.render.SceneFactory
import dev.geode.render.offscreen.OffscreenRenderSpec
import dev.geode.render.offscreen.OffscreenSceneRenderer
import dev.geode.render.offscreen.StyleSwitches
import dev.geode.util.bestEffort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

enum class ExportCodec(
    val mimeType: String,
) {
    H264(MediaFormat.MIMETYPE_VIDEO_AVC),
    HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC),
    ;

    /** This codec when the device has an encoder for it, otherwise H.264, which every device has. */
    fun available(): ExportCodec = if (this == H264 || hasEncoder(mimeType)) this else H264

    companion object {
        fun hasEncoder(mimeType: String): Boolean =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }
            }
    }
}

enum class ExportQuality(
    val shortSide: Int,
    val bitRate: Int,
) {
    HD720(720, 6_000_000),
    FHD1080(1080, 12_000_000),
    UHD4K(2160, 40_000_000),
}

enum class ExportRatio(
    val label: String,
    val wRatio: Int,
    val hRatio: Int,
) {
    R16_9("16:9", 16, 9),
    R9_16("9:16", 9, 16),
    R1_1("1:1", 1, 1),
    R4_5("4:5", 4, 5),
    R4_3("4:3", 4, 3),
    R21_9("21:9", 21, 9),
}

class ExportAspect(
    val width: Int,
    val height: Int,
    val bitRate: Int,
) {
    companion object {
        fun of(
            quality: ExportQuality,
            ratio: ExportRatio,
        ): ExportAspect {
            val short = quality.shortSide
            val landscape = ratio.wRatio >= ratio.hRatio
            var longSide = (short.toLong() * maxOf(ratio.wRatio, ratio.hRatio) / minOf(ratio.wRatio, ratio.hRatio)).toInt()
            var shortSide = short
            if (longSide > MAX_AVC_DIM) {
                shortSide = (short.toLong() * MAX_AVC_DIM / longSide).toInt()
                longSide = MAX_AVC_DIM
            }
            val w = if (landscape) longSide else shortSide
            val h = if (landscape) shortSide else longSide
            return ExportAspect(even(w), even(h), quality.bitRate)
        }

        private const val MAX_AVC_DIM = 4096

        private fun even(v: Int): Int = if (v % 2 == 0) v else v + 1
    }
}

class VideoExporter(
    private val context: Context,
) {
    companion object {
        const val FPS: Int = 60
        private const val TIMEOUT_US: Long = 10_000

        private const val FLUSH_ATTEMPT_LIMIT = 1_000

        /**
         * The frame rates a Render may ask for. A Render is not bound to real time, so the range is wide;
         * a rate the encoder refuses falls back to 30 fps (see [encodeInto]).
         */
        val FRAME_RATES: IntRange = 12..120
    }

    /**
     * One video to make: the track and its whole-song analysis (ADR-0001), the Style ([sceneFactory])
     * and the look to draw it with, and its format. [look] is the frozen Customization as the engine
     * reads it (#337): a Render draws everything the live Visual would for it.
     */
    class Job(
        val audioUri: Uri,
        val timeline: FeatureTimeline,
        val sceneFactory: SceneFactory,
        val look: CustomizationSnapshot,
        val aspect: ExportAspect,
        val fps: Int = FPS,
        val range: ExportRange? = null,
        val loopSafe: Boolean = false,
        val codec: ExportCodec = ExportCodec.H264,
        /** How many times over the scene is drawn before it is brought to the frame (#345). */
        val supersample: Float = 1f,
        /** The look's Style switching, replayed frame by frame (#343); none keeps one Style throughout. */
        val switches: StyleSwitches? = null,
    )

    sealed interface Result {
        data class Saved(
            val uri: Uri,
            /** How long the finished video runs. */
            val lengthMs: Long,
            /**
             * How the finished file's loudness compares to [loudnessTarget], or null when it could
             * not be measured (e.g. no audio track). Geode cannot yet apply the resulting gain — see
             * [measureLoudness] — so this is read-only telemetry for now, not an applied correction.
             */
            val loudnessAdvice: LoudnessAdvice? = null,
        ) : Result

        data class Failed(
            val message: String,
        ) : Result

        data object Cancelled : Result
    }

    /**
     * Makes [job]'s video: into [destination] when one is given, otherwise as [fileName] in the phone's
     * Videos library under `Movies/Geode`. [pace] runs after every frame, so a caller can slow the
     * Render while the live Visual is on screen (ADR-0004).
     */
    suspend fun export(
        job: Job,
        fileName: String,
        destination: Uri? = null,
        loudnessTarget: LoudnessTarget = LoudnessTarget.LeaveAsIs,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
        pace: () -> Unit = {},
    ): Result =
        withContext(Dispatchers.Default) {
            val encode = { pfd: ParcelFileDescriptor -> encodeInto(pfd, job, onProgress, isCancelled, pace) }
            if (destination != null) {
                return@withContext exportToDestination(destination, encode, loudnessTarget, isCancelled)
            }
            val resolver = context.contentResolver
            val values =
                ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Geode")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            val outUri =
                resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    ?: return@withContext Result.Failed(
                        "Your Videos library would not accept a new file. Check that storage is not full, " +
                            "or render to a folder you choose instead.",
                    )
            val pfd = resolver.openFileDescriptor(outUri, "w")
            if (pfd == null) {
                bestEffort(TAG, "resolver.delete(outUri, null, null)") { resolver.delete(outUri, null, null) }
                return@withContext Result.Failed("The new file in your Videos library could not be opened for writing.")
            }
            try {
                val lengthMs = pfd.use(encode)
                if (isCancelled()) {
                    bestEffort(TAG, "resolver.delete(outUri, null, null)") { resolver.delete(outUri, null, null) }
                    Result.Cancelled
                } else {
                    val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    resolver.update(outUri, done, null, null)
                    Result.Saved(outUri, lengthMs, measureLoudness(outUri, loudnessTarget))
                }
            } catch (e: Exception) {
                bestEffort(TAG, "resolver.delete(outUri, null, null)") { resolver.delete(outUri, null, null) }
                throw e
            }
        }

    private suspend fun exportToDestination(
        destination: Uri,
        encode: (ParcelFileDescriptor) -> Long,
        loudnessTarget: LoudnessTarget,
        isCancelled: () -> Boolean,
    ): Result {
        val resolver = context.contentResolver
        val pfd =
            resolver.openFileDescriptor(destination, "w")
                ?: return Result.Failed(
                    "The folder you chose would not let the file be written. Some cloud providers refuse " +
                        "this; try your Videos library or a folder on the device.",
                )
        return try {
            val lengthMs = pfd.use(encode)
            if (isCancelled()) {
                bestEffort(
                    TAG,
                    "DocumentsContract.deleteDocument(resolver, de...",
                ) { DocumentsContract.deleteDocument(resolver, destination) }
                Result.Cancelled
            } else {
                Result.Saved(destination, lengthMs, measureLoudness(destination, loudnessTarget))
            }
        } catch (e: Exception) {
            bestEffort(TAG, "DocumentsContract.deleteDocument(resolver, de...") { DocumentsContract.deleteDocument(resolver, destination) }
            throw e
        }
    }

    /**
     * Measures the audio Geode just muxed into [uri] and, if it decoded, turns that measurement
     * into advice for [target].
     *
     * This deliberately re-decodes the finished file rather than the source: [LoudnessMeter] is
     * built to measure "the thing that will actually be uploaded", and by the time [encodeInto]
     * has run, the AAC track already lives inside a container [LoudnessMeter] can open directly —
     * unlike [AudioTranscoder]'s intermediate `.bin`, which is a raw elementary stream with no
     * container framing for [android.media.MediaExtractor] to parse. Geode does not yet have
     * anywhere upstream to apply the resulting gain: [AudioTranscoder] owns the only PCM Geode
     * ever holds for this file, and turning [LoudnessAdvice.gainDb] into an actual correction
     * means scaling samples inside its decode loop, which this unit does not touch. So today this
     * only reports what a platform would do to the file as exported.
     *
     * A file the encoder just finished writing successfully is not allowed to be reported as a
     * failed export over a problem in this purely-informational re-read, so any exception here is
     * swallowed to "no advice" rather than left to propagate into the caller's `catch`, which would
     * delete the file that was just saved.
     */
    private suspend fun measureLoudness(
        uri: Uri,
        target: LoudnessTarget,
    ): LoudnessAdvice? {
        val result =
            try {
                LoudnessMeter(context).measure(uri)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Post-export loudness measurement failed", e)
                return null
            }
        return (result as? LoudnessResult.Measured)?.let { LoudnessTargets.advise(it.report, target) }
    }

    /** Encodes [job] into [pfd] and returns how long the video runs, in milliseconds. */
    private fun encodeInto(
        pfd: ParcelFileDescriptor,
        job: Job,
        onProgress: (Float) -> Unit,
        isCancelled: () -> Boolean,
        pace: () -> Unit,
    ): Long {
        val aspect = job.aspect
        val timeline = job.timeline
        val range = job.range
        var encoderRef: MediaCodec? = null
        var inputSurfaceRef: android.view.Surface? = null
        var muxerRef: MediaMuxer? = null
        var aacRef: AudioTranscoder.Result? = null
        var eglRef: EncoderSurface? = null
        var rendererRef: OffscreenSceneRenderer? = null
        var audioFeedRef: AudioFeed? = null
        var pcmRef: TrackPcm? = null
        var muxerStarted = false
        var muxerStopped = false
        try {
            val requestedFpsBounded = job.fps.coerceIn(FRAME_RATES)
            // The requested codec at the requested rate first, then its reduced form, then the same pair on H.264.
            val attempts =
                listOf(job.codec.available(), ExportCodec.H264).distinct().flatMap { c ->
                    listOf(EncoderAttempt(c, requestedFpsBounded, aspect.bitRate), EncoderAttempt(c, 30, aspect.bitRate * 2 / 3))
                }
            val (encoder, fps) = openEncoder(aspect, attempts)
            encoderRef = encoder
            val inputSurface = encoder.createInputSurface().also { inputSurfaceRef = it }
            encoder.start()

            val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxerRef = it }
            // A ranged Render starts on the frame of the track's own grid nearest its start, so its frames are
            // the same frames a whole-track Render draws there, and its sound starts with that frame (#344).
            val firstFrame = OffscreenRenderSpec.firstFrameAt(range?.startMs ?: 0L, fps)
            val rangeStartMs = OffscreenRenderSpec.frameMs(firstFrame, fps)
            val aac =
                AudioTranscoder(context)
                    .transcode(
                        uri = job.audioUri,
                        maxDurationMs = range?.durationMs ?: 0L,
                        startMs = rangeStartMs,
                        isCancelled = isCancelled,
                    ) { onProgress(it * 0.1f) }
                    .also { aacRef = it }
            val egl = EncoderSurface(inputSurface).also { eglRef = it }
            egl.makeCurrent()

            val sourceDurationUs = if (aac.durationUs > 0) aac.durationUs else timeline.durationMs * 1000
            val exportDurationUs =
                if (job.loopSafe) {
                    dev.geode.analysis.BarTrim
                        .trimToBars(sourceDurationUs, timeline.bpm)
                } else {
                    sourceDurationUs
                }
            val totalFrames = (exportDurationUs * fps / 1_000_000L).toInt().coerceAtLeast(1)
            val frameDurationNs = 1_000_000_000L / fps

            // The track decoded in step with the frames, so the scenes hear the real sound (#342).
            val pcm = TrackPcm(context, job.audioUri).also { pcmRef = it }
            val renderer =
                OffscreenSceneRenderer(
                    context = context,
                    sceneFactory = job.sceneFactory,
                    timeline = timeline,
                    pcm = pcm,
                    switches = job.switches,
                    spec =
                        OffscreenRenderSpec(
                            width = aspect.width,
                            height = aspect.height,
                            fps = fps,
                            totalFrames = totalFrames,
                            firstFrame = firstFrame,
                            look = job.look,
                            supersample = job.supersample,
                        ),
                ).also { rendererRef = it }
            renderer.prepare()
            // Everything before the range is drawn first, unseen, so the range opens warm (#344).
            val work = (firstFrame + totalFrames).toFloat()
            renderer.preRoll(isCancelled) { done, _ ->
                onProgress(0.1f + done / work * 0.85f)
                pace()
            }

            var videoTrack = -1
            var audioTrack = -1
            val info = MediaCodec.BufferInfo()

            for (frame in 0 until totalFrames) {
                if (isCancelled()) break
                renderer.renderFrame(frame)
                val timeMs = frame * 1000L / fps
                egl.setPresentationTimeNs(frame * frameDurationNs)
                egl.swapBuffers()

                while (true) {
                    val outIndex = encoder.dequeueOutputBuffer(info, 0)
                    if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        audioTrack = muxer.addTrack(aac.format)
                        muxer.start()
                        muxerStarted = true
                    } else if (outIndex >= 0) {
                        val buf = checkNotNull(encoder.getOutputBuffer(outIndex)) { "video encoder buffer null (codec error state)" }
                        writeSample(muxer, videoTrack, buf, info, muxerStarted)
                        encoder.releaseOutputBuffer(outIndex, false)
                    } else {
                        break
                    }
                }
                if (muxerStarted && audioTrack >= 0) {
                    val feed =
                        audioFeedRef ?: AudioFeed(muxer, audioTrack, aac, exportDurationUs).also { audioFeedRef = it }
                    feed.writeUpTo(timeMs * 1000L)
                }
                onProgress(0.1f + (firstFrame + frame) / work * 0.85f)
                pace()
            }
            encoder.signalEndOfInputStream()
            var flushAttempts = 0
            var sawEos = false
            drain@ while (flushAttempts < FLUSH_ATTEMPT_LIMIT) {
                val outIndex = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrack = muxer.addTrack(encoder.outputFormat)
                        audioTrack = muxer.addTrack(aac.format)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val buf = checkNotNull(encoder.getOutputBuffer(outIndex)) { "video encoder buffer null (codec error state)" }
                        writeSample(muxer, videoTrack, buf, info, muxerStarted)
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (eos) {
                            sawEos = true
                            break@drain
                        }
                    }
                    else -> flushAttempts++
                }
            }
            check(muxerStarted || isCancelled()) { "Video encoder produced no output (encoder/format unsupported?)" }
            check(sawEos || isCancelled()) { "Video encoder stalled while flushing - export incomplete" }
            if (muxerStarted && !isCancelled() && audioTrack >= 0) {
                val feed =
                    audioFeedRef ?: AudioFeed(muxer, audioTrack, aac, exportDurationUs).also { audioFeedRef = it }
                feed.writeUpTo(Long.MAX_VALUE)
                onProgress(1f)
            }
            if (muxerStarted && !isCancelled()) {
                muxer.stop()
                muxerStopped = true
            }
            return exportDurationUs / MICROS_PER_MILLI
        } finally {
            bestEffort(TAG, "rendererRef?.release()") { rendererRef?.release() }
            bestEffort(TAG, "pcmRef?.close()") { pcmRef?.close() }
            bestEffort(TAG, "audioFeedRef?.close()") { audioFeedRef?.close() }
            if (muxerStarted && !muxerStopped) runCatching { muxerRef?.stop() }
            bestEffort(TAG, "muxerRef?.release()") { muxerRef?.release() }
            bestEffort(TAG, "encoderRef?.stop()") { encoderRef?.stop() }
            bestEffort(TAG, "encoderRef?.release()") { encoderRef?.release() }
            bestEffort(TAG, "inputSurfaceRef?.release()") { inputSurfaceRef?.release() }
            bestEffort(TAG, "eglRef?.release()") { eglRef?.release() }
            aacRef?.release()
        }
    }

    private class EncoderAttempt(
        val codec: ExportCodec,
        val fps: Int,
        val bitRate: Int,
    )

    private fun videoFormat(
        aspect: ExportAspect,
        attempt: EncoderAttempt,
    ): MediaFormat =
        MediaFormat.createVideoFormat(attempt.codec.mimeType, aspect.width, aspect.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, attempt.bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, attempt.fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

    /** The first attempt the device's encoder accepts, with the frame rate it was configured for. */
    private fun openEncoder(
        aspect: ExportAspect,
        attempts: List<EncoderAttempt>,
    ): Pair<MediaCodec, Int> {
        var failure: Exception? = null
        for (attempt in attempts) {
            val candidate = MediaCodec.createEncoderByType(attempt.codec.mimeType)
            try {
                candidate.configure(videoFormat(aspect, attempt), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return candidate to attempt.fps
            } catch (e: Exception) {
                bestEffort(TAG, "candidate.release()") { candidate.release() }
                failure = e
            }
        }
        throw checkNotNull(failure)
    }

    private fun writeSample(
        muxer: MediaMuxer,
        track: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        started: Boolean,
    ) {
        if (!started || track < 0 || info.size <= 0) return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        muxer.writeSampleData(track, buffer, info)
    }

    private class AudioFeed(
        private val muxer: MediaMuxer,
        private val track: Int,
        private val aac: AudioTranscoder.Result,
        private val limitUs: Long,
    ) : java.io.Closeable {
        private val raf = java.io.RandomAccessFile(aac.file, "r")
        private val info = MediaCodec.BufferInfo()
        private var scratch = ByteBuffer.allocate(64 * 1024)
        private var next = 0

        fun writeUpTo(upToUs: Long) {
            val channel = raf.channel
            while (next < aac.sampleInfos.size) {
                val sample = aac.sampleInfos[next]
                if (sample.presentationTimeUs >= upToUs) return
                if (sample.presentationTimeUs >= limitUs) {
                    next = aac.sampleInfos.size
                    return
                }
                next++
                if (scratch.capacity() < sample.size) scratch = ByteBuffer.allocate(sample.size)
                scratch.clear()
                scratch.limit(sample.size)
                var read = 0
                while (read < sample.size) {
                    val n = channel.read(scratch, sample.offset + read)
                    if (n <= 0) break
                    read += n
                }
                scratch.flip()
                info.set(0, read, sample.presentationTimeUs, sample.flags)
                muxer.writeSampleData(track, scratch, info)
            }
        }

        override fun close() {
            bestEffort(TAG, "raf.close()") { raf.close() }
        }
    }
}

private const val TAG = "VideoExporter"
private const val MICROS_PER_MILLI = 1_000L
