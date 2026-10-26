package dev.geode.feature.customization.api

import dev.geode.engine.types.Millis

/** One Modulator, unique within a Customization. */
@JvmInline
value class ModulatorId(
    val value: Int,
) {
    override fun toString(): String = "modulator $value"
}

/** The parts of a Modulator that a Reaction, another Modulator or a Macro can move. */
enum class ModulatorParameter {
    RATE,
    DEPTH,

    /** A step sequencer's pattern offset, a wave's shape; whatever the kind treats as its pattern. */
    PATTERN,
}

enum class Wave {
    SINE,
    TRIANGLE,
    SAW,
    SQUARE,
    RANDOM,
}

/**
 * The kinds of Modulator: the four of #11 and the two beat Modulators of #319. They are generated, not
 * measured, so they are not Sound signals.
 */
sealed interface ModulatorShape {
    /** An LFO wave, one cycle per [period], or locked to the beat when [beatSync]. */
    data class Lfo(
        val wave: Wave,
        val period: Millis,
        val beatSync: Boolean = false,
    ) : ModulatorShape

    /** An envelope that fires when [signal] crosses [threshold]. */
    data class HitEnvelope(
        val signal: SoundSignal,
        val attack: Millis,
        val release: Millis,
        val threshold: Float,
    ) : ModulatorShape

    /** A step sequencer: each step is a value 0..1, held for [stepLength] or one beat when [beatSync]. */
    data class StepSequencer(
        val steps: List<Float>,
        val stepLength: Millis,
        val beatSync: Boolean = false,
    ) : ModulatorShape

    /** A smooth random drift that wanders over about [period]. */
    data class RandomDrift(
        val period: Millis,
    ) : ModulatorShape

    /**
     * Flips between 0 and 1 every [everyBeats] beats the analysis hears, gliding to each side over
     * [glide]. It counts heard beats and never assumes a tempo; with no beat it holds.
     */
    data class ToggleOnBeat(
        val everyBeats: Int = 1,
        val glide: Millis = ModulatorShape.DEFAULT_GLIDE,
    ) : ModulatorShape

    /**
     * Moves to a new random value 0..1 every [everyBeats] heard beats, gliding there over [glide]. The
     * values come from the Modulator's own seed, so a Render gives the same ones every time.
     */
    data class RandomOnBeat(
        val everyBeats: Int = 1,
        val glide: Millis = ModulatorShape.DEFAULT_GLIDE,
    ) : ModulatorShape

    companion object {
        /** The most beats a beat Modulator waits between changes: the engine's step count. */
        const val MAX_EVERY_BEATS: Int = 16

        /** A beat Modulator's glide unless the person sets another: quick, but never a step. */
        val DEFAULT_GLIDE: Millis = Millis(120)
    }
}

/** When a Modulator runs, once it is linked to something. */
sealed interface StartMode {
    /** Always, while the music plays. */
    data object WhilePlaying : StartMode

    /** Restarted each time [signal] hits. */
    data class OnSound(
        val signal: SoundSignal,
    ) : StartMode

    /** Only between the person's play and stop. */
    data object ByHand : StartMode
}

/**
 * A generated value that moves targets through Reactions whose source it is. A Modulator that no
 * Reaction listens to does not run. [rate] scales its speed and [depth] its swing; [playing] is the
 * play and stop of a [StartMode.ByHand] Modulator.
 */
data class Modulator(
    val id: ModulatorId,
    val shape: ModulatorShape,
    val start: StartMode = StartMode.WhilePlaying,
    val rate: Float = DEFAULT_RATE,
    val depth: Float = 1f,
    val playing: Boolean = false,
) {
    companion object {
        const val MIN_RATE: Float = 0.25f
        const val DEFAULT_RATE: Float = 1f
        const val MAX_RATE: Float = 4f
    }
}
