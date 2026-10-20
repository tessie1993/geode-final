package dev.geode.engine.audio

/**
 * Reads a [SampleRing] forward, so that whoever drives it sees every frame the ring receives once
 * and in order: live analysis is driven by the samples that arrive, not by a clock.
 *
 * The first read starts [startBehindFrames] behind the writer rather than at the ring's oldest
 * frame, so a reader that attaches mid-stream does not replay a backlog. When the ring begins a
 * new epoch the cursor starts that epoch at its first frame. When the writer has lapped the
 * cursor, it resumes at the oldest frame still intact.
 */
class SampleCursor(
    private val ring: SampleRing,
    private val startBehindFrames: Int,
) {
    init {
        require(startBehindFrames >= 0) { "startBehindFrames must not be negative, was $startBehindFrames" }
    }

    private var epoch = UNPLACED
    private var next = 0L

    /** Interleaved channels per frame in the last [read]: the source's, at most the ring's. */
    var channels: Int = 1
        private set

    /** Copies the frames that arrived since the last read into [out], interleaved; returns how many. */
    fun read(out: FloatArray): Int {
        // The epoch is read before the write position: beginEpoch() resets the position first.
        val epochNow = ring.epoch
        val written = ring.writtenFrames
        if (epochNow != epoch) {
            next = if (epoch == UNPLACED) maxOf(0L, written - startBehindFrames) else 0L
            epoch = epochNow
        }
        // Behind the writer: the epoch is about to change under us; the next read starts it.
        if (next > written) return 0
        next = maxOf(next, ring.oldestAvailable)
        val ch = ring.sourceChannelCount.coerceIn(1, ring.channelCount)
        val count = minOf(written - next, (out.size / ch).toLong()).toInt()
        if (count <= 0) return 0
        ring.copyInterleaved(next, count, ch, out)
        // The ring restarted while we copied: what we have is not this epoch's audio.
        if (ring.epoch != epochNow) return 0
        next += count
        channels = ch
        return count
    }

    private companion object {
        const val UNPLACED = Int.MIN_VALUE
    }
}
