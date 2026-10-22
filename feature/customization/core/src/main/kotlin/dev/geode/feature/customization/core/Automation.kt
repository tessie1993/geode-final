package dev.geode.feature.customization.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Autopilot
import dev.geode.feature.customization.api.Cadence
import dev.geode.feature.customization.api.CameraMove
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.Sequenced
import dev.geode.feature.customization.api.StylePool
import dev.geode.feature.customization.api.StyleSwitching
import kotlin.math.PI
import kotlin.math.sin

/** Four beats to the bar. */
const val BEATS_PER_BAR: Int = 4

/**
 * The reference meaning of the Automations the engine evaluates (ADR-0002), like [Evaluation]: when
 * one is due, what it moves and how. Beats are counted from 0 at the start of the music, and every
 * value is a fraction 0..1 of its target's range. Style switching is the one the reducer carries out,
 * since a new Style brings its own Customization ([CustomizationEvent.StyleDue]).
 */
object Automation {
    /** Whether [cadence] acts on beat [beat], where [bigMoment] says whether a surge or a change was heard on it. */
    fun due(
        cadence: Cadence,
        beat: Int,
        bigMoment: Boolean,
    ): Boolean =
        when (cadence) {
            Cadence.BigMoments -> bigMoment
            is Cadence.EveryBars -> beat > 0 && beat % (cadence.bars * BEATS_PER_BAR) == 0
        }

    /**
     * The Style [switching] moves to from [current]: the one after it in its pool, wrapping round, so a
     * Render repeats. Only [picks] (the app's picks, every Style this version knows) can be chosen;
     * null when there is no other Style to go to.
     */
    fun nextStyle(
        switching: StyleSwitching,
        current: SceneId,
        picks: List<SceneId>,
    ): SceneId? {
        val mine = switching.mine.filter { it in picks }
        val pool =
            when (switching.pool) {
                StylePool.MINE -> mine
                StylePool.PICKS -> picks
                StylePool.BOTH -> (mine + picks).distinct()
            }
        val at = pool.indexOf(current)
        return (1..pool.size).map { pool[(at + it) % pool.size] }.firstOrNull { it != current }
    }

    /**
     * How far the crossfade into the new Style is, 0..1, [elapsed] into a [transition]: a smooth
     * S-curve. Its steepest point moves 1.5 / [transition] per millisecond, so a transition of at least
     * [StyleSwitching.MIN_TRANSITION] changes overall brightness far slower than the No-flash minimum.
     */
    fun crossfade(
        elapsed: Millis,
        transition: Millis,
    ): Float {
        if (transition <= Millis.ZERO) return 1f
        val x = Rules.unit(elapsed.value.toFloat() / transition.value)
        return x * x * (SMOOTH_A - SMOOTH_B * x)
    }

    /** The palette after [palette], wrapping round to the first. */
    fun nextPalette(palette: Float): Float = ((palette.toInt() + 1) % (SharedProperty.PALETTE.max.toInt() + 1)).toFloat()

    /**
     * Where [move] puts its Camera property whose own value is [base], on beat [beat] at [phase] 0..1
     * through it: pushed by its amount on its beat, easing back before the next one.
     */
    fun camera(
        base: Float,
        move: CameraMove,
        beat: Int,
        phase: Float,
    ): Float {
        if (beat % move.beats != 0) return base
        val left = 1f - Rules.unit(phase)
        return Rules.unit(base + move.amount * left * left)
    }

    /** The step [sequenced] holds its target at on beat [beat]. */
    fun step(
        sequenced: Sequenced,
        beat: Int,
    ): Float = sequenced.steps[(beat / sequenced.beats) % sequenced.steps.size]

    /** Where [pilot] puts its Macro, set by the person to [centre], [bars] bars into the music. */
    fun autopilot(
        centre: Float,
        pilot: Autopilot,
        bars: Float,
    ): Float = Rules.unit(centre + pilot.width * sin(2 * PI.toFloat() * bars / pilot.bars))

    private const val SMOOTH_A = 3f
    private const val SMOOTH_B = 2f
}
