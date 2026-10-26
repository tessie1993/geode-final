package dev.geode.feature.customization.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.LayerLoop
import dev.geode.feature.customization.api.LoopShape
import dev.geode.feature.customization.api.LoopTarget
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionId
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.ResponseCurve
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.Wave

/**
 * Layer 2's loop as the engine runs it: one Modulator and a Reaction from it onto each looped Shared
 * core property, in the same mod matrix as every other Modulator (no second LFO system). The engine
 * steps them by real time, so a loop runs the same at any frame rate; the Reactions' rise and the
 * engine's own slew on Layer 2 keep it to the No-flash rule.
 */
object LayerLoops {
    /** The levels [LoopShape.BEAT_STEPS] steps through, one per step. */
    val STEPS: List<Float> = listOf(1f, 0.35f, 0.7f, 0f)

    /** How long a stepped loop glides into each new level: never a cut. */
    val GLIDE = Millis(400)

    /**
     * A beat-synced loop's length when no tempo is heard yet: one beat at 120 BPM. The engine counts
     * whole beats of the live tempo once it hears one; the Modulator's rate stretches a beat to the
     * loop's length.
     */
    val BEAT_FALLBACK = Millis(500)

    /** The Modulator that drives [loop], numbered [id]. */
    fun modulator(
        loop: LayerLoop,
        id: ModulatorId,
    ): Modulator {
        val rate = loop.rate
        val length = if (rate.beatSynced) BEAT_FALLBACK else rate.length
        val shape =
            when (loop.shape) {
                LoopShape.SINE -> ModulatorShape.Lfo(Wave.SINE, length, rate.beatSynced)
                LoopShape.TRIANGLE, LoopShape.PING_PONG -> ModulatorShape.Lfo(Wave.TRIANGLE, length, rate.beatSynced)
                LoopShape.BEAT_STEPS -> ModulatorShape.StepSequencer(STEPS, length, rate.beatSynced)
            }
        val speed = if (rate.beatSynced) 1f / rate.beats else Modulator.DEFAULT_RATE
        return Rules.modulator(Modulator(id = id, shape = shape, rate = speed))
    }

    /**
     * One Reaction per target of [loop], from the Modulator [id], in [LoopTarget]'s order. Opacity and
     * influence dip from the person's own value by the loop's depth, the swap rises from it. Ping-pong
     * eases the triangle at its turns; steps glide.
     */
    fun reactions(
        loop: LayerLoop,
        id: ModulatorId,
    ): List<Reaction> {
        val glide = if (loop.shape == LoopShape.BEAT_STEPS) GLIDE else Millis.ZERO
        val curve = if (loop.shape == LoopShape.PING_PONG) ResponseCurve.S_CURVE else ResponseCurve.LINEAR
        return LoopTarget.entries.filter { it in loop.targets }.map { target ->
            Rules.reaction(
                Reaction(
                    id = ReactionId(0),
                    source = ReactionSource.Generated(id),
                    target = Target.Shared(target.property),
                    amount = loop.depth,
                    invert = target.dips,
                    rise = glide,
                    fall = glide,
                    curve = curve,
                ),
            )
        }
    }
}

/**
 * How the plan names a Style for the engine: FNV-1a over the id's UTF-8 bytes, folded to 24 bits so a
 * float holds it exactly, and never 0 (no Style). The engine's layer::styleHash gives the same number.
 */
object StyleHash {
    private const val OFFSET = 0x811C9DC5.toInt()
    private const val PRIME = 0x01000193
    private const val LOW_24 = 0xFFFFFF
    private const val FOLD = 24
    private const val BYTE = 0xFF

    fun of(style: SceneId): Int {
        var hash = OFFSET
        for (byte in style.value.toByteArray(Charsets.UTF_8)) {
            hash = (hash xor (byte.toInt() and BYTE)) * PRIME
        }
        val folded = (hash ushr FOLD) xor (hash and LOW_24)
        return if (folded == 0) 1 else folded
    }
}
