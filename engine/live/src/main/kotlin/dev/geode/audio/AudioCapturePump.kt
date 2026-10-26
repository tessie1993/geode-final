package dev.geode.audio

import android.media.AudioFormat
import android.media.AudioRecord
import androidx.annotation.AnyThread
import dev.geode.util.bestEffort
import kotlin.concurrent.thread

abstract class AudioCapturePump(
    private val sink: dev.geode.engine.audio.PcmSink,
    defaultRateHz: Int,
    protected val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    @Volatile
    private var record: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var runGeneration = 0

    val active: Boolean get() = running

    /**
     * Called on the pump's thread when a capture ends by itself, because reads kept failing after
     * every retry. Never called for a capture that [stop] ended.
     */
    @Volatile
    var onEnded: (() -> Unit)? = null

    /**
     * Called on the pump's thread when a read failed and the capture was reopened, with the rate it now
     * runs at, before anything at that rate is written: the audio before and after are not continuous.
     */
    @Volatile
    var onRestarted: ((Int) -> Unit)? = null

    @Volatile
    var sampleRateHz: Int = defaultRateHz
        protected set

    @Volatile
    protected var lastAudibleAtMs: Long = 0L

    /** The record being read now; it changes when a failed read reopens the capture. */
    protected val currentRecord: AudioRecord? get() = record

    protected abstract val threadName: String

    protected abstract fun noteLevel(
        buffer: FloatArray,
        count: Int,
        startedAtMs: Long,
    )

    protected abstract fun resetLevel()

    /** A fresh record for a retry after a failed read, or null when the source will not open now. */
    protected abstract fun reopen(): AudioRecord?

    /** Called on the pump's thread with each record a retry starts reading. */
    protected open fun onRecordReplaced(rec: AudioRecord) = Unit

    /** Called by [stop] once the capture has stopped. */
    protected open fun onStopped() = Unit

    @AnyThread
    @Synchronized
    protected fun startPump(
        rec: AudioRecord,
        channels: Int,
        onSampleRate: (Int) -> Unit,
    ): Boolean {
        if (!startRecording(rec)) {
            android.util.Log.w(javaClass.simpleName, "startRecording refused")
            release(rec)
            return false
        }
        record = rec
        val generation = ++runGeneration
        running = true
        lastAudibleAtMs = 0L
        resetLevel()
        onSampleRate(sampleRateHz)
        worker = thread(name = threadName, isDaemon = true) { pump(rec, channels, generation) }
        return true
    }

    private fun owns(generation: Int) = running && runGeneration == generation

    private fun pump(
        first: AudioRecord,
        channels: Int,
        generation: Int,
    ) {
        val floats = FloatArray(LiveInputPolicy.READ_FRAMES * channels)
        val shorts = ShortArray(LiveInputPolicy.READ_FRAMES * channels)
        val startedAt = nowMs()
        var rec = first
        // Failed reads since the last good one: a capture that keeps failing gives up after the retries.
        var failures = 0
        // Set when the retries ran out: the last record is already released.
        var ended = false
        while (!ended && owns(generation)) {
            val n = read(rec, floats, shorts)
            // stop() bumps runGeneration before it returns, so a read that was already blocked when
            // stop() was called but only unblocks afterwards lands here with a stale generation — skip
            // writing into a sink this run no longer owns.
            if (runGeneration != generation) break
            if (n > 0) {
                failures = 0
                val frames = n / channels
                if (frames > 0) {
                    sink.write(floats, frames, channels)
                    noteLevel(floats, n, startedAt)
                }
            } else if (n < 0) {
                android.util.Log.w(javaClass.simpleName, "AudioRecord.read error $n")
                release(rec)
                val next = recover(generation, failures)
                if (next == null) {
                    ended = true
                } else {
                    rec = next.first
                    failures = next.second
                }
            }
        }
        // This worker is the sole owner of `rec`; release it regardless of whether the generation
        // moved on, since nobody else will.
        if (!ended) release(rec)
        if (runGeneration == generation) {
            running = false
            onEnded?.invoke()
        }
    }

    /**
     * Reopens the capture after a failed read, waiting longer before each attempt (LiveInputPolicy),
     * with [failures] attempts already spent. The new record and the attempts spent, or null when the
     * retries ran out or the capture was stopped meanwhile.
     */
    private fun recover(
        generation: Int,
        failures: Int,
    ): Pair<AudioRecord, Int>? {
        var attempt = failures
        while (owns(generation)) {
            val delay = LiveInputPolicy.retryDelayMs(attempt++) ?: return null
            runCatching { Thread.sleep(delay) }
            if (!owns(generation)) return null
            val rec = reopen()
            if (rec != null && startRecording(rec)) {
                record = rec
                if (!owns(generation)) {
                    release(rec)
                    return null
                }
                onRecordReplaced(rec)
                onRestarted?.invoke(sampleRateHz)
                return rec to attempt
            }
            rec?.let { release(it) }
        }
        return null
    }

    private fun read(
        rec: AudioRecord,
        floats: FloatArray,
        shorts: ShortArray,
    ): Int =
        if (rec.audioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
            rec.read(floats, 0, floats.size, AudioRecord.READ_BLOCKING)
        } else {
            val read = rec.read(shorts, 0, shorts.size)
            for (i in 0 until read) floats[i] = shorts[i] / 32768f
            read
        }

    private fun startRecording(rec: AudioRecord): Boolean =
        runCatching { rec.startRecording() }.isSuccess && rec.recordingState == AudioRecord.RECORDSTATE_RECORDING

    private fun release(rec: AudioRecord) {
        bestEffort(TAG, "rec.stop()") { rec.stop() }
        bestEffort(TAG, "rec.release()") { rec.release() }
    }

    @AnyThread
    @Synchronized
    fun stop() {
        running = false
        // Invalidate the generation the worker captured at start. If join() below times out
        // with the worker still inside a blocking rec.read(), this lets it notice on return
        // and exit without writing into the sink or touching a record a later run now owns.
        runGeneration++
        record?.let { runCatching { it.stop() } }
        worker?.let { runCatching { it.join(500) } }
        worker = null
        record = null
        resetLevel()
        onStopped()
    }

    protected companion object {
        const val BUFFER_MULTIPLIER = 4

        const val SILENCE_EPSILON = 1e-6f

        const val SILENCE_GRACE_MS = 4_000L
    }
}

private const val TAG = "AudioCapturePump"
