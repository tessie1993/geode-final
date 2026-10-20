package dev.geode.analysis

import dev.geode.engine.audio.SoundEvents

data class AudioFeatures(
    val bands: FloatArray,
    val waveform: FloatArray,
    val rms: Float = 0f,
    val bass: Float = 0f,
    val mid: Float = 0f,
    val treble: Float = 0f,
    val onset: Float = 0f,
    val beat: Boolean = false,
    val bpm: Float = 0f,
    val centroid: Float = 0f,
    val progress: Float = 0f,
    val sectionIndex: Int = 0,
    val sectionCount: Int = 0,
    val flux: Float = 0f,
    val beatStrength: Float = 0f,
    val beatPhase: Float = 0f,
    val transient: Float = 0f,
    val pulseConfidence: Float = 0f,
    val macroEnergy: Float = 0f,
    val kick: Float = 0f,
    val snare: Float = 0f,
    val hat: Float = 0f,
    val stereoWidth: Float = 0f,
    val stereoCorrelation: Float = 1f,
    val stereoPan: Float = 0f,
    val chroma: FloatArray = EMPTY_CHROMA,
    val chromaConfidence: Float = 0f,
    // The musical-structure half of the native frame. The analyser has always
    // computed these; until they were carried here FeatureFrameCodec zero-filled
    // them, so no scene ever saw a drop, a section, a bar or a buildup.
    val tempoStability: Float = 0f,
    val barPhase: Float = 0f,
    val beatInBar: Int = 0,
    val downbeat: Boolean = false,
    val downbeatConfidence: Float = 0f,
    val novelty: Float = 0f,
    val sectionBoundary: Boolean = false,
    val buildup: Float = 0f,
    val drop: Boolean = false,
    val arrival: Boolean = false,
    val harmonicity: Float = HARMONICITY_UNDECIDED,
    val warmup: Float = 0f,
    // beat, beatStrength, transient, kick, snare, hat, downbeat, sectionBoundary, drop and arrival
    // are one-hop impulses. A live frame also carries every occurrence so far, sequenced, for a
    // reader that looks less often than once per hop (DisplayEvents); offline frames leave it empty.
    val events: SoundEvents = SoundEvents.NONE,
) {
    val hasChroma: Boolean get() = chroma.size == 12

    val beatImpulse: Float
        get() =
            when {
                !beat -> 0f
                beatStrength > 0f -> beatStrength
                else -> 1f
            }

    val motionImpulse: Float
        get() = maxOf(beatImpulse, transient * TRANSIENT_MOTION_WEIGHT)

    /** These features with no one-hop event in them: what a stretch in which nothing fired reports. */
    fun withoutEvents(): AudioFeatures =
        copy(
            beat = false,
            beatStrength = 0f,
            transient = 0f,
            kick = 0f,
            snare = 0f,
            hat = 0f,
            downbeat = false,
            sectionBoundary = false,
            drop = false,
            arrival = false,
        )

    /**
     * These features with every one-hop event of [other] folded in, as when two hops become one:
     * flags or'ed, strengths maxed, and onset and flux maxed with them.
     */
    fun foldedWith(other: AudioFeatures): AudioFeatures =
        copy(
            beat = beat || other.beat,
            beatStrength = maxOf(beatStrength, other.beatStrength),
            transient = maxOf(transient, other.transient),
            kick = maxOf(kick, other.kick),
            snare = maxOf(snare, other.snare),
            hat = maxOf(hat, other.hat),
            downbeat = downbeat || other.downbeat,
            sectionBoundary = sectionBoundary || other.sectionBoundary,
            drop = drop || other.drop,
            arrival = arrival || other.arrival,
            onset = maxOf(onset, other.onset),
            flux = maxOf(flux, other.flux),
        )

    companion object {
        const val TRANSIENT_MOTION_WEIGHT = 0.5f

        /** What the native HarmonicBalance reports before it has decided (its kUndecided). */
        const val HARMONICITY_UNDECIDED = 0.5f

        val EMPTY_CHROMA = FloatArray(0)

        fun empty(
            bandCount: Int = 64,
            waveformSize: Int = 128,
        ): AudioFeatures = AudioFeatures(FloatArray(bandCount), FloatArray(waveformSize))
    }
}
