package dev.geode.feature.customization.api

/**
 * The Sound signals a Reaction can listen to (#10). Each is measured live from what has already been
 * heard. Before any Reaction uses one, the normaliser stage brings it to 0..1 ([SignalTrim]); hidden
 * hit behaviour is gone, so anything that reacts to a hit does it through a Reaction (#11).
 */
enum class SoundSignal {
    LEVEL,
    BASS,
    MID,
    TREBLE,

    /** Spectral brightness. */
    BRIGHTNESS,

    /** A full-band hit, against the recent past. */
    HIT,
    KICK,

    /** The mid-band hit (not a snare detector). */
    MID_HIT,
    HAT,

    /** An on-grid onset once the tempo is confident. */
    BEAT,

    /** Slow loudness against the last half minute or so. */
    ENERGY,
    BUILD_UP,

    /** A sudden jump in energy: what used to be called a drop. */
    SURGE,

    /** A structural change in the music. */
    CHANGE,
    STEREO_WIDTH,
    STEREO_PAN,
}

/**
 * The normaliser's setting for one Sound signal: the raw value is scaled by [gain], values below
 * [floor] read as 0, and the result is clamped to 0..1. The neutral trim changes nothing.
 */
data class SignalTrim(
    val gain: Float = NEUTRAL_GAIN,
    val floor: Float = 0f,
) {
    companion object {
        const val NEUTRAL_GAIN: Float = 1f
        const val MIN_GAIN: Float = 0f
        const val MAX_GAIN: Float = 4f

        /** The highest floor; above it a signal would hardly ever move. */
        const val MAX_FLOOR: Float = 0.9f

        val NEUTRAL: SignalTrim = SignalTrim()
    }
}
