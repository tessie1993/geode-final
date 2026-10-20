package dev.geode.analysis

import dev.geode.engine.audio.MidSideWindow
import dev.geode.engine.audio.ReactiveAnalyzer
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
            hopRateHz = HOP_RATE_HZ,
        )

    // Serialises every native call on [analyzer]: the loop's tick on Dispatchers.Default, tuning
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
     * A one-hop pulse held for the hops a display frame can span.
     *
     * The analyser fires `beat`, `transient`, `kick`, `snare`, `hat`, `downbeat`, `sectionBoundary`,
     * `drop` and `arrival` for exactly one 16 ms hop. [features] is a StateFlow, which conflates,
     * and the renderer reads it once per display frame, so at 30 fps (the thermal governor's
     * paced rate) every other pulse used to be lost before any scene saw it. Each pulse now
     * stays in the emitted frame for [PULSE_HOLD_HOPS] hops, long enough for any consumer
     * sampling at 20 Hz or better. Consumers that must fire once per pulse edge-detect already
     * (`live::Edge` in the fluid emitters); the rest take a max-envelope, for which a held
     * value is the same value.
     */
    private class PulseHold {
        var level = 0f
            private set
        private var hopsLeft = 0

        fun step(value: Float): Float {
            if (value > 0f) {
                level = maxOf(level, value)
                hopsLeft = PULSE_HOLD_HOPS
            } else if (hopsLeft > 0) {
                hopsLeft--
                if (hopsLeft == 0) level = 0f
            }
            return level
        }

        fun reset() {
            level = 0f
            hopsLeft = 0
        }
    }

    internal inner class Pass {
        private val window = MidSideWindow(ring, fftSize)
        private val beat = PulseHold()
        private val beatStrength = PulseHold()
        private val transient = PulseHold()
        private val kick = PulseHold()
        private val snare = PulseHold()
        private val hat = PulseHold()
        private val downbeat = PulseHold()
        private val sectionBoundary = PulseHold()
        private val drop = PulseHold()
        private val arrival = PulseHold()

        fun reset() {
            analyzer.reset()
            listOf(beat, beatStrength, transient, kick, snare, hat, downbeat, sectionBoundary, drop, arrival)
                .forEach(PulseHold::reset)
        }

        fun tick(): Boolean {
            if (!window.refresh()) return false
            analyzer.analyze(window.mid, window.side, DT_SECONDS)

            _features.value =
                AudioFeatures(
                    bands = analyzer.bands.copyOf(),
                    waveform = analyzer.waveform.copyOf(),
                    rms = analyzer.rms,
                    bass = analyzer.bass,
                    mid = analyzer.mid,
                    treble = analyzer.treble,
                    onset = analyzer.onset,
                    beat = beat.step(if (analyzer.beat) 1f else 0f) > 0f,
                    bpm = analyzer.bpm,
                    centroid = analyzer.centroid,
                    flux = analyzer.fluxValue,
                    beatStrength = beatStrength.step(analyzer.beatStrength),
                    transient = transient.step(analyzer.transient),
                    beatPhase = analyzer.beatPhase,
                    pulseConfidence = analyzer.pulseConfidence,
                    macroEnergy = analyzer.macroEnergy,
                    kick = kick.step(analyzer.kick),
                    snare = snare.step(analyzer.snare),
                    hat = hat.step(analyzer.hat),
                    chroma = analyzer.chroma.copyOf(),
                    chromaConfidence = analyzer.chromaConfidence,
                    stereoWidth = analyzer.stereoWidth,
                    stereoCorrelation = analyzer.stereoCorrelation,
                    stereoPan = analyzer.stereoPan,
                    tempoStability = analyzer.tempoStability,
                    barPhase = analyzer.barPhase,
                    beatInBar = analyzer.beatInBar,
                    downbeat = downbeat.step(if (analyzer.downbeat) 1f else 0f) > 0f,
                    downbeatConfidence = analyzer.downbeatConfidence,
                    novelty = analyzer.novelty,
                    sectionBoundary = sectionBoundary.step(if (analyzer.sectionBoundary) 1f else 0f) > 0f,
                    buildup = analyzer.buildup,
                    drop = drop.step(if (analyzer.drop) 1f else 0f) > 0f,
                    arrival = arrival.step(if (analyzer.arrival) 1f else 0f) > 0f,
                    harmonicity = analyzer.harmonicity,
                    warmup = analyzer.warmup,
                )
            return true
        }
    }

    // job is mutated from AudioBus.onInterestChanged, which can fire on whatever thread calls
    // AudioBus.addConsumer()/removeConsumer() - not necessarily the analysis scope's thread -
    // so every read/write of it is synchronised on jobLock.
    private val jobLock = Any()
    private var job: Job? = null

    // Set before close() cancels the loop's job, and checked by the loop before it re-enters
    // pass.tick() (which makes native analyzer.analyze calls): close() is not required to wait
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
        private const val TICK_NS = 16_000_000L

        // Three hops is 48 ms: one 30 fps display frame plus scheduling jitter.
        private const val PULSE_HOLD_HOPS = 3

        internal const val HOP_RATE_HZ = 1000f / 16f
        internal const val DT_SECONDS = 16f / 1000f

        const val DEFAULT_BAND_COUNT = 64

        const val DEFAULT_FFT_SIZE = 2048

        const val DEFAULT_ATTACK = 0.6f
        const val DEFAULT_DECAY = 0.12f
    }
}
