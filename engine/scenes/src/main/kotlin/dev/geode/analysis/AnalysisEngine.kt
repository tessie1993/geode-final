package dev.geode.analysis

import dev.geode.engine.audio.AnalysisHop
import dev.geode.engine.audio.ReactiveAnalyzer
import dev.geode.engine.audio.SampleCursor
import dev.geode.engine.audio.SampleRing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AnalysisEngine(
    private val ring: SampleRing,
    val bandCount: Int = DEFAULT_BAND_COUNT,
    private val fftSize: Int = DEFAULT_FFT_SIZE,
) {
    private val analyzer =
        ReactiveAnalyzer(
            bandCount = bandCount,
            fftSize = fftSize,
            hopRateHz = AnalysisHop.RATE_HZ,
        )

    // Serialises every native call on [analyzer]: the loop's pass on Dispatchers.Default, tuning
    // setters from Main or the playback thread, and the destroy in close(). The native session is
    // not thread-safe, and a stop()/start() pair can briefly leave the old loop finishing a tick.
    private val nativeLock = Any()

    @Volatile
    var sampleRateHz: Int = 44100
        set(value) {
            field = value
            synchronized(nativeLock) { analyzer.sampleRateHz = value }
        }

    var attack: Float = DEFAULT_ATTACK
        set(value) {
            field = value
            synchronized(nativeLock) { analyzer.attackSeconds = BeatTuning.envelopeSeconds(value) }
        }

    var decay: Float = DEFAULT_DECAY
        set(value) {
            field = value
            synchronized(nativeLock) { analyzer.releaseSeconds = BeatTuning.envelopeSeconds(value) }
        }

    var beatSensitivity: Float = BeatTuning.SENSITIVITY_DEFAULT
        set(value) {
            field = BeatTuning.clampSensitivity(value)
            synchronized(nativeLock) { analyzer.sensitivity = field }
        }

    var beatMinIntervalMs: Float = BeatTuning.INTERVAL_MS_DEFAULT
        set(value) {
            field = BeatTuning.clampIntervalMs(value)
            synchronized(nativeLock) { analyzer.refractoryMs = field }
        }

    private val _features = MutableStateFlow(AudioFeatures.empty(bandCount))
    val features: StateFlow<AudioFeatures> = _features

    @Volatile
    private var resetPending = false

    init {
        attack = DEFAULT_ATTACK
        decay = DEFAULT_DECAY
    }

    fun reset() {
        resetPending = true
        _features.value = AudioFeatures.empty(bandCount)
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
            if (analysed) _features.value = snapshot()
            return analysed
        }

        private fun snapshot(): AudioFeatures =
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

    companion object {
        // How often the loop looks for new audio. It sets latency only: every hop the ring
        // received since the last look is analysed then, however many there are.
        private const val TICK_NS = 16_000_000L

        const val DEFAULT_BAND_COUNT = 64

        const val DEFAULT_FFT_SIZE = 2048

        const val DEFAULT_ATTACK = 0.6f
        const val DEFAULT_DECAY = 0.12f
    }
}
