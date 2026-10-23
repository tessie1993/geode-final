package dev.geode.engine.audio

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class SampleRing(
    val capacityFrames: Int,
    val channelCount: Int,
    val maxWriteFrames: Int = capacityFrames / DEFAULT_RUNWAY_DIVISOR,
) : PcmSink {
    init {
        require(capacityFrames > 0 && capacityFrames and (capacityFrames - 1) == 0) {
            "capacityFrames must be a power of two, was $capacityFrames"
        }
        require(channelCount > 0) { "channelCount must be positive, was $channelCount" }
        require(maxWriteFrames in 1..capacityFrames) {
            "maxWriteFrames must be between 1 and $capacityFrames, was $maxWriteFrames"
        }
    }

    private val channels: Array<FloatArray> = Array(channelCount) { FloatArray(capacityFrames) }
    private val mask: Long = (capacityFrames - 1).toLong()

    // The write position and the epoch are atomic: beginEpoch() runs on another thread than write(), and
    // a write that straddles it must not put back the position the new epoch just cleared.
    private val writtenRef = AtomicLong(0)
    private val epochRef = AtomicInteger(0)

    private val written: Long get() = writtenRef.get()

    val writtenFrames: Long get() = written

    val epoch: Int get() = epochRef.get()

    @Volatile
    private var sourceChannels: Int = 0

    val sourceChannelCount: Int get() = sourceChannels

    val oldestAvailable: Long get() = maxOf(0L, written + maxWriteFrames - capacityFrames)

    fun beginEpoch() {
        writtenRef.set(0)
        epochRef.incrementAndGet()
    }

    override fun write(
        interleaved: FloatArray,
        frameCount: Int,
        sourceChannelCount: Int,
    ) {
        require(sourceChannelCount > 0) { "sourceChannelCount must be positive" }
        require(frameCount.toLong() * sourceChannelCount <= interleaved.size) {
            "$frameCount frames x $sourceChannelCount channels exceeds ${interleaved.size}"
        }
        require(frameCount <= maxWriteFrames) {
            "$frameCount frames exceeds maxWriteFrames of $maxWriteFrames"
        }
        sourceChannels = sourceChannelCount
        val startEpoch = epochRef.get()
        val start = written
        var w = start
        var read = 0
        repeat(frameCount) {
            val slot = (w and mask).toInt()
            for (c in 0 until channelCount) {
                channels[c][slot] = if (c < sourceChannelCount) interleaved[read + c] else 0f
            }
            read += sourceChannelCount
            w++
        }
        // A beginEpoch() during this write wins: these frames belonged to the epoch it ended.
        if (epochRef.get() == startEpoch) writtenRef.compareAndSet(start, w)
    }

    fun snapshotLatest(out: Array<FloatArray>): Boolean {
        val frames = out.minOf { it.size }
        if (frames > capacityFrames) return false
        val w = written
        if (w < frames) return false
        for (c in out.indices.take(channelCount)) {
            val src = channels[c]
            val dst = out[c]
            var r = w - frames
            for (i in 0 until frames) {
                dst[i] = src[(r and mask).toInt()]
                r++
            }
        }
        return true
    }

    companion object {
        const val DEFAULT_RUNWAY_DIVISOR = 4
    }

    internal fun copyInterleaved(
        firstSample: Long,
        count: Int,
        channelsOut: Int,
        out: FloatArray,
    ) {
        var r = firstSample
        var w = 0
        repeat(count) {
            val slot = (r and mask).toInt()
            for (c in 0 until channelsOut) out[w++] = channels[c][slot]
            r++
        }
    }

    internal fun copyInto(
        firstSample: Long,
        count: Int,
        out: Array<FloatArray>,
    ) {
        for (c in out.indices.take(channelCount)) {
            val src = channels[c]
            val dst = out[c]
            var r = firstSample
            for (i in 0 until count) {
                dst[i] = src[(r and mask).toInt()]
                r++
            }
        }
    }
}
