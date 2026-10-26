package dev.geode.engine.audio

/** An audio focus change, as Android's AudioManager reports it. */
enum class FocusChange {
    GAIN,
    LOSS,
    LOSS_TRANSIENT,
    LOSS_TRANSIENT_CAN_DUCK,
    ;

    companion object {
        // AudioManager.AUDIOFOCUS_*; this module has no Android SDK.
        private const val AUDIOFOCUS_GAIN = 1
        private const val AUDIOFOCUS_LOSS = -1
        private const val AUDIOFOCUS_LOSS_TRANSIENT = -2
        private const val AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK = -3

        /** The change an AudioManager focus code stands for; null for a code that is not a change. */
        fun fromAndroid(code: Int): FocusChange? =
            when (code) {
                AUDIOFOCUS_GAIN -> GAIN
                AUDIOFOCUS_LOSS -> LOSS
                AUDIOFOCUS_LOSS_TRANSIENT -> LOSS_TRANSIENT
                AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> LOSS_TRANSIENT_CAN_DUCK
                else -> null
            }
    }
}

/**
 * Where Geode's own player stands with audio focus: whether another app holds it for a while (a call:
 * the music waits, still meant to play) and whether the music is ducked under another sound.
 */
data class FocusState(
    val suppressed: Boolean = false,
    val ducked: Boolean = false,
)

/** What a focus change does: the new state, and whether the listener's play is called off. */
data class FocusOutcome(
    val state: FocusState,
    val stopPlaying: Boolean,
)

/**
 * The native player's audio focus rules, the ones ExoPlayer follows for its own: a permanent loss
 * (another player took over) pauses for good; a transient loss (a call, an alarm) pauses until focus
 * comes back, then plays on; a loss that allows ducking plays on at [DUCK_VOLUME]; a gain undoes both.
 */
object AudioFocusPolicy {
    /** The volume, as a share of the listener's own, while another sound ducks the music. */
    const val DUCK_VOLUME: Float = 0.2f

    fun onChange(
        state: FocusState,
        change: FocusChange,
    ): FocusOutcome =
        when (change) {
            FocusChange.GAIN -> FocusOutcome(FocusState(), stopPlaying = false)
            FocusChange.LOSS -> FocusOutcome(FocusState(), stopPlaying = true)
            FocusChange.LOSS_TRANSIENT -> FocusOutcome(state.copy(suppressed = true, ducked = false), stopPlaying = false)
            FocusChange.LOSS_TRANSIENT_CAN_DUCK -> FocusOutcome(state.copy(ducked = true), stopPlaying = false)
        }

    /** Whether the engine should be playing: the listener wants it and no transient loss holds it back. */
    fun engineRuns(
        playWhenReady: Boolean,
        state: FocusState,
    ): Boolean = playWhenReady && !state.suppressed

    /** The volume the engine plays at: the listener's, ducked while another sound asks for it. */
    fun engineVolume(
        volume: Float,
        state: FocusState,
    ): Float = if (state.ducked) volume * DUCK_VOLUME else volume
}
