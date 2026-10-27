package dev.geode.analysis

import dev.geode.engine.audio.AnalysisHop
import dev.geode.engine.audio.ReactiveAnalyzer
import dev.geode.engine.audio.SampleCursor
import dev.geode.engine.audio.SampleRing
import dev.geode.engine.bridge.NativeSnapshots
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The live analysis: a loop on its own dispatcher that hands the native session every frame the
 * [SampleRing] receives, once and in order, and publishes the newest frame's features to [features].
 * The Visual reads them through `AudioBus` on its own thread.
 *
 * Until Settings or a Customization tune it, the analysis starts with [START_SENSITIVITY] and
 * [START_REFRACTORY_MS], the values it has always started with.
 */
class AnalysisEngine internal constructor(
    private val ring: SampleRing,
    private val analyzer: Analyzer,
    val bandCount: Int = DEFAULT_BAND_COUNT,
    private val fftSize: Int = DEFAULT_FFT_SIZE,
) {
    constructor(
        ring: SampleRing,
        bandCount: Int = DEFAULT_BAND_COUNT,
        fftSize: Int = DEFAULT_FFT_SIZE,
    ) : this(ring, NativeAnalyzer(ReactiveAnalyzer(bandCount = bandCount, fftSize = fftSize, hopRateHz = AnalysisHop.RATE_HZ)), bandCount, fftSize)

    // Serialises every native call on [analyzer]: the loop's pass on Dispatchers.Default, tuning
    // setters from Main or the playback thread, and the destroy in close(). The native session is
    // not thread-safe, and a stop()/start() pair can briefly leave the old loop finishing a tick.
    private val nativeLock = Any()

    @Volatile
    var sampleRateHz: Int = DEFAULT_RATE_HZ
        set(value) {
            field = value
            synchronized(nativeLock) { if (!closed) analyzer.sampleRateHz = value }
        }

    var attack: Float = DEFAULT_ATTACK
        set(value) {
            field = value
            pushTuning()
        }

    var decay: Float = DEFAULT_DECAY
        set(value) {
            field = value
            pushTuning()
        }

    var beatSensitivity: Float = START_SENSITIVITY
        set(value) {
            field = BeatTuning.clampSensitivity(value)
            pushTuning()
        }

    var beatMinIntervalMs: Float = START_REFRACTORY_MS
        set(value) {
            field = BeatTuning.clampIntervalMs(value)
            pushTuning()
        }

    private val _features = MutableStateFlow(AudioFeatures.empty(bandCount))
    val features: StateFlow<AudioFeatures> = _features

    @Volatile
    private var resetPending = false

    init {
        pushTuning()
    }

    /** The analysis starts over from the next hop: a seek, a new song, a new source. */
    fun reset() {
        resetPending = true
        _features.value = AudioFeatures.empty(bandCount)
    }

    private fun pushTuning() {
        synchronized(nativeLock) {
            if (closed) return
            analyzer.setTuning(
                sensitivity = beatSensitivity,
                refractoryMs = beatMinIntervalMs,
                attackSeconds = BeatTuning.envelopeSeconds(attack),
                releaseSeconds = BeatTuning.envelopeSeconds(decay),
            )
        }
    }

    /**
     * One pass over the audio the ring received since the previous one.
     *
     * Analysis is driven by sample count: [cursor] hands the native session every frame the ring
     * receives exactly once, the session analyses one hop per [AnalysisHop] of audio with
     * dt = hop / sample rate, and live and offline therefore share one hop definition. Only the newest
     * hop is published, because [features] conflates anyway; one-hop events are not held across hops
     * for readers that look less often than once per hop, they are sequenced in
     * [AudioFeatures.events], which [DisplayEvents] edge-detects once per display frame.
     */
    internal inner class Pass {
        private val cursor = SampleCursor(ring, startBehindFrames = fftSize)
        private val pcm = FloatArray(fftSize * ring.channelCount)

        fun reset() {
            analyzer.reset()
        }

        fun tick(): Boolean {
            var analysed = false
            while (true) {
                val frames = cursor.read(pcm)
                if (frames == 0) break
                analyzer.push(pcm, frames, cursor.channels)
                while (analyzer.pull()) analysed = true
            }
            if (analysed) _features.value = analyzer.snapshot()
            return analysed
        }
    }

    // job is mutated from AudioBus.onInterestChanged, which can fire on whatever thread calls
    // AudioBus.addConsumer()/removeConsumer() - not necessarily the analysis scope's thread -
    // so every read/write of it is synchronised on jobLock.
    private val jobLock = Any()
    private var job: Job? = null

    // Set before close() cancels the loop's job, and checked by the loop before it re-enters
    // pass.tick() (which makes native push/pull calls): close() is not required to wait
    // for job.cancel() to take effect, so this flag closes most of the window in which the loop
    // could still be inside a native call - or about to start one - after analyzer.close() has
    // destroyed the native handle. It does not close that window entirely; closeAndJoin() does.
    @Volatile
    private var closed = false

    fun start(scope: CoroutineScope) {
        synchronized(jobLock) {
            if (job?.isActive == true) return
            job =
                scope.launch(Dispatchers.Default) {
                    val pass = Pass()
                    var deadlineNs = System.nanoTime()
                    while (!closed) {
                        synchronized(nativeLock) {
                            // Re-checked under the lock: close() may have destroyed the handle.
                            if (closed) return@launch
                            if (resetPending) {
                                resetPending = false
                                pass.reset()
                            }
                            pass.tick()
                        }
                        deadlineNs += TICK_NS
                        val now = System.nanoTime()
                        if (deadlineNs < now) deadlineNs = now
                        // Never delay(0): it returns without suspending, so a tick that
                        // overruns the budget would leave this loop with no suspension
                        // point at all - uncancellable, and spinning a core flat out.
                        delay(maxOf(1L, (deadlineNs - now) / 1_000_000))
                    }
                }
        }
    }

    /**
     * From the next hop on, the analysis picks up the Sound-signal tuning of each Customization
     * [snapshots] receives; null detaches. Detach before the snapshots are closed.
     */
    fun attachSnapshots(snapshots: NativeSnapshots?) {
        synchronized(nativeLock) { if (!closed) analyzer.attachSnapshots(snapshots) }
    }

    fun stop() {
        synchronized(jobLock) {
            job?.cancel()
            job = null
        }
    }

    /** Does not wait for the loop, but the destroy waits for any in-flight tick (nativeLock) and the loop exits on [closed]. */
    fun close() {
        closed = true
        stop()
        synchronized(nativeLock) { analyzer.close() }
    }

    /** Cancels the loop and waits for it to actually stop before destroying the native handle, so no in-flight native call can race the destroy. */
    suspend fun closeAndJoin() {
        closed = true
        val current = synchronized(jobLock) { job.also { job = null } }
        current?.cancelAndJoin()
        synchronized(nativeLock) { analyzer.close() }
    }

    /** The native session behind the loop, behind a seam so the loop runs in JVM tests. */
    internal interface Analyzer : AutoCloseable {
        var sampleRateHz: Int

        fun setTuning(
            sensitivity: Float,
            refractoryMs: Float,
            attackSeconds: Float,
            releaseSeconds: Float,
        )

        fun reset()

        fun attachSnapshots(snapshots: NativeSnapshots?)

        /** [frames] frames of [interleaved] with [channels] channels each, analysed as they arrive. */
        fun push(
            interleaved: FloatArray,
            frames: Int,
            channels: Int,
        )

        /** True while a hop's frame is ready. */
        fun pull(): Boolean

        /** The newest frame's features. */
        fun snapshot(): AudioFeatures
    }

    private class NativeAnalyzer(
        private val analyzer: ReactiveAnalyzer,
    ) : Analyzer {
        override var sampleRateHz: Int
            get() = analyzer.sampleRateHz
            set(value) {
                analyzer.sampleRateHz = value
            }

        override fun setTuning(
            sensitivity: Float,
            refractoryMs: Float,
            attackSeconds: Float,
            releaseSeconds: Float,
        ) {
            analyzer.sensitivity = sensitivity
            analyzer.refractoryMs = refractoryMs
            analyzer.attackSeconds = attackSeconds
            analyzer.releaseSeconds = releaseSeconds
        }

        override fun reset() = analyzer.reset()

        override fun attachSnapshots(snapshots: NativeSnapshots?) = analyzer.attachSnapshots(snapshots)

        override fun push(
            interleaved: FloatArray,
            frames: Int,
            channels: Int,
        ) = analyzer.push(interleaved, frames, channels)

        override fun pull(): Boolean = analyzer.pull()

        override fun snapshot(): AudioFeatures =
            AudioFeatures(
                bands = analyzer.bands.copyOf(),
                waveform = analyzer.waveform.copyOf(),
                rms = analyzer.rms,
                bass = analyzer.bass,
                mid = analyzer.mid,
                treble = analyzer.treble,
                onset = analyzer.onset,
                beat = analyzer.beat,
                bpm = analyzer.bpm,
                centroid = analyzer.centroid,
                flux = analyzer.fluxValue,
                beatStrength = analyzer.beatStrength,
                transient = analyzer.transient,
                beatPhase = analyzer.beatPhase,
                pulseConfidence = analyzer.pulseConfidence,
                macroEnergy = analyzer.macroEnergy,
                kick = analyzer.kick,
                snare = analyzer.snare,
                hat = analyzer.hat,
                chroma = analyzer.chroma.copyOf(),
                chromaConfidence = analyzer.chromaConfidence,
                stereoWidth = analyzer.stereoWidth,
                stereoCorrelation = analyzer.stereoCorrelation,
                stereoPan = analyzer.stereoPan,
                tempoStability = analyzer.tempoStability,
                barPhase = analyzer.barPhase,
                beatInBar = analyzer.beatInBar,
                downbeat = analyzer.downbeat,
                downbeatConfidence = analyzer.downbeatConfidence,
                novelty = analyzer.novelty,
                sectionBoundary = analyzer.sectionBoundary,
                buildup = analyzer.buildup,
                drop = analyzer.drop,
                arrival = analyzer.arrival,
                harmonicity = analyzer.harmonicity,
                warmup = analyzer.warmup,
                events = analyzer.events,
            )

        override fun close() = analyzer.close()
    }

    companion object {
        // How often the loop looks for new audio. It sets latency only: every hop the ring
        // received since the last look is analysed then, however many there are.
        private const val TICK_NS = 16_000_000L

        const val DEFAULT_BAND_COUNT = 64

        const val DEFAULT_FFT_SIZE = 2048

        /** The rate the analysis runs at until a source says otherwise. */
        const val DEFAULT_RATE_HZ = 48_000

        const val DEFAULT_ATTACK = 0.6f
        const val DEFAULT_DECAY = 0.12f

        /** The beat sensitivity the live analysis starts with, before anything tunes it. */
        const val START_SENSITIVITY = 3f

        /** The shortest gap between beats the live analysis starts with, before anything tunes it. */
        const val START_REFRACTORY_MS = 60f
    }
}
