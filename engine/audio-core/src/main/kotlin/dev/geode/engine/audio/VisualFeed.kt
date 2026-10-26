package dev.geode.engine.audio

import dev.geode.engine.bridge.GeodeNative
import dev.geode.engine.bridge.NativeFailures
import dev.geode.engine.bridge.NativeSnapshots

/** Who writes the Visual's audio; the codes are GEODE_FEED_* in geode_api.h. */
enum class FeedSource(
    val code: Int,
) {
    /** The player's tap: Geode's own engine or ExoPlayer, whichever the session opened. */
    PLAYER(1),

    /** Live input: the microphone. */
    LIVE(2),
}

/**
 * The Visual's one audio feed (geode_feed, core/api/Feed.hpp): a native stereo ring every source
 * writes into, exactly one [FeedSource] listened to at a time, the analysis running natively off it,
 * and the read each Visual frame makes on the drawing thread at the audio being heard.
 *
 * Nothing on the way from a source to the Visual passes through Kotlin objects or the main thread:
 * the native player writes from its audio callback, ExoPlayer's tap and the microphone through
 * [sink], and the Visual reads natively once it is attached ([GeodeNative.vizAttachFeed]).
 *
 * The analysis runs while at least one Visual watches: each one [acquire]s the feed while it is bound
 * and [release]s it after.
 */
class VisualFeed internal constructor(
    private val backend: Backend,
) {
    constructor() : this(NativeBackend(GeodeNative.feedCreate()))

    /** The native handle a Visual or the native player attaches to; 0 when the engine could not create it. */
    val handle: Long get() = backend.handle

    private var consumers = 0

    fun select(source: FeedSource) = backend.call("feedSelect") { h -> GeodeNative.feedSelect(h, source.code) }

    /** The source dropped the audio it had buffered ahead (a seek): what follows starts over. */
    fun flush(source: FeedSource) = backend.call("feedFlush") { h -> GeodeNative.feedFlush(h, source.code) }

    /** A paused source is silent at once, whatever it had buffered ahead. */
    fun setRunning(
        source: FeedSource,
        running: Boolean,
    ) = backend.call("feedSetRunning") { h -> GeodeNative.feedSetRunning(h, source.code, running) }

    /** How far ahead of the speaker [source] writes, in microseconds of its audio, and the speed it is heard at. */
    fun setPresentation(
        source: FeedSource,
        delayUs: Long,
        speed: Float,
    ) = backend.call("feedSetPresentation") { h -> GeodeNative.feedSetPresentation(h, source.code, delayUs, speed) }

    fun setTuning(
        sensitivity: Float,
        refractoryMs: Float,
        attackSeconds: Float,
        releaseSeconds: Float,
    ) {
        backend.call("feedSetTuning") { h -> GeodeNative.feedSetTuning(h, sensitivity, refractoryMs, attackSeconds, releaseSeconds) }
    }

    fun resetAnalysis() = backend.call("feedResetAnalysis") { h -> GeodeNative.feedResetAnalysis(h) }

    /** From the next hop on, the analysis takes the Sound-signal tuning of each Customization [snapshots] receives. */
    fun attachSnapshots(snapshots: NativeSnapshots?) =
        backend.call("feedAttachSnapshots") { h -> GeodeNative.feedAttachSnapshots(h, snapshots?.handle ?: 0L) }

    /** Where [source] writes its PCM; one per source and writer thread. */
    fun sink(source: FeedSource): Sink = Sink(source)

    /** A Visual starts watching: the analysis runs from the first one on. */
    @Synchronized
    fun acquire() {
        consumers++
        if (consumers == 1) backend.call("feedSetActive") { h -> GeodeNative.feedSetActive(h, true) }
    }

    /** A Visual stops watching: the analysis stops with the last one. */
    @Synchronized
    fun release() {
        if (consumers == 0) return
        consumers--
        if (consumers == 0) backend.call("feedSetActive") { h -> GeodeNative.feedSetActive(h, false) }
    }

    val watched: Boolean
        @Synchronized get() = consumers > 0

    /** [PcmSink] for one source; [rateHz] is the rate of what it writes, set before the writes at that rate. */
    inner class Sink internal constructor(
        val source: FeedSource,
    ) : PcmSink {
        @Volatile
        var rateHz: Int = 0

        override fun write(
            interleaved: FloatArray,
            frameCount: Int,
            sourceChannelCount: Int,
        ) {
            val rate = rateHz
            if (rate <= 0 || frameCount <= 0 || sourceChannelCount <= 0) return
            backend.write(source, interleaved, frameCount, sourceChannelCount, rate)
        }
    }

    /** The native side, behind a seam so the bookkeeping here runs in JVM tests. */
    internal interface Backend {
        val handle: Long

        /** Runs [native] on the handle and reports a failed status; nothing happens on a 0 handle. */
        fun call(
            name: String,
            native: (Long) -> Int,
        )

        fun write(
            source: FeedSource,
            interleaved: FloatArray,
            frames: Int,
            channels: Int,
            rateHz: Int,
        )
    }

    private class NativeBackend(
        override val handle: Long,
    ) : Backend {
        override fun call(
            name: String,
            native: (Long) -> Int,
        ) {
            if (handle != 0L) NativeFailures.check(native(handle), name)
        }

        override fun write(
            source: FeedSource,
            interleaved: FloatArray,
            frames: Int,
            channels: Int,
            rateHz: Int,
        ) {
            if (handle != 0L) GeodeNative.feedWrite(handle, source.code, interleaved, frames, channels, rateHz)
        }
    }
}
