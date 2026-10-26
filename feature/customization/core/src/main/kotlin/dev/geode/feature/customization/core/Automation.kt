package dev.geode.feature.customization.core

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Cadence
import dev.geode.feature.customization.api.CameraMove
import dev.geode.feature.customization.api.Lane
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.StylePool
import dev.geode.feature.customization.api.StyleSwitching
import dev.geode.feature.customization.api.Trigger
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** Four beats to the bar. */
const val BEATS_PER_BAR: Int = 4

/**
 * The reference meaning of the Automations the engine evaluates (ADR-0002), like [Evaluation]: when
 * one is due, what it moves and how. Everything is live: beats are the whole beats the live analysis
 * has reported, counted from 0 at the start of the music, and before it reports a tempo there are none,
 * so nothing beat-timed moves. Every value is a fraction 0..1 of its target's range. Style switching is
 * the one the reducer carries out, since a new Style brings its own Customization
 * ([CustomizationEvent.StyleDue]).
 */
object Automation {
    /**
     * Whether [trigger] fires now. [beat] is the whole beat that has just begun, or null when none has
     * (or no tempo is known yet); [bigMoment] is the louder of Surge and Change now, and [armed] says
     * whether it was below the threshold before, so one big moment fires once.
     */
    fun triggered(
        trigger: Trigger,
        beat: Int?,
        bigMoment: Float,
        armed: Boolean,
    ): Boolean =
        when (trigger) {
            is Trigger.BigMoments -> armed && bigMoment >= trigger.threshold
            is Trigger.EveryBars -> beat != null && beat > 0 && beat % (trigger.bars * BEATS_PER_BAR) == 0
        }

    /**
     * The Level (0..1, as heard, before the trims) under which Style switching takes the music for
     * silence (#361): it never switches then, so a pause, the gap between tracks or a quiet intro never
     * switches. The one value to retarget to the analysis' measured noise floor (#278); the engine's
     * kSilenceLevel is the same.
     */
    const val SILENCE_LEVEL: Float = 0.02f

    /** How long silence lasts, in played time, before Style switching's spacing starts again when sound returns. */
    val SILENCE: Millis = Millis(2_500)

    /** Whether Style switching takes a [level] (as heard) for silence. */
    fun silent(level: Float): Boolean = level < SILENCE_LEVEL

    /** Whether [cadence] lets a move happen [sinceLast] after its last one (null: there was none yet). */
    fun spaced(
        cadence: Cadence,
        sinceLast: Millis?,
    ): Boolean = sinceLast == null || sinceLast >= cadence.spacing

    /** Whether [cadence] moves now: its trigger fired and its spacing has passed (see [triggered], [spaced]). */
    fun due(
        cadence: Cadence,
        beat: Int?,
        bigMoment: Float,
        armed: Boolean,
        sinceLast: Millis?,
    ): Boolean = triggered(cadence.trigger, beat, bigMoment, armed) && spaced(cadence, sinceLast)

    /** Style switching's next Style, and how many switches it makes [played] up to. */
    data class Pick(
        val style: SceneId,
        val played: Int,
    )

    /**
     * Where [switching] goes from [current] after [played] switches. Its pool, round after round:
     * - [StylePool.VISUALIZER]: the [visualizer] styles this version draws, in their order;
     * - [StylePool.PICKS]: every one of [builtIns] in a shuffle seeded by the switching's seed and the
     *   round, so none comes again until all have played, and a Render repeats exactly;
     * - [StylePool.BOTH]: the Visualizer styles, then the shuffle of the other built-in Styles.
     *
     * A pick that is the Style already showing is passed over. Null when the pool has nowhere else to go.
     */
    fun nextStyle(
        switching: StyleSwitching,
        current: SceneId,
        visualizer: List<SceneId>,
        builtIns: List<SceneId>,
        played: Int,
    ): Pick? {
        val listed = visualizer.filter { it in builtIns }.distinct()
        val round = { n: Int ->
            when (switching.pool) {
                StylePool.VISUALIZER -> listed
                StylePool.PICKS -> shuffled(builtIns, switching.seed, n)
                StylePool.BOTH -> listed + shuffled(builtIns - listed.toSet(), switching.seed, n)
            }
        }
        val size = round(0).size
        if (size == 0) return null
        val start = maxOf(played, 0)
        return (start..start + size)
            .map { Pick(round(it / size)[it % size], it + 1) }
            .firstOrNull { it.style != current }
    }

    /** [styles] in the shuffle [seed] gives round [round]. */
    fun shuffled(
        styles: List<SceneId>,
        seed: Long,
        round: Int,
    ): List<SceneId> = styles.shuffled(Random(seed * SEED_STRIDE + round))

    /**
     * How far a crossfade of [length] is, 0..1, [elapsed] into it: a smooth S-curve. Its steepest point
     * moves 1.5 / [length] per millisecond, so a crossfade of at least [StyleSwitching.MIN_TRANSITION]
     * changes overall brightness far slower than the No-flash minimum. Style switching and palette
     * changes both fade this way.
     */
    fun crossfade(
        elapsed: Millis,
        length: Millis,
    ): Float {
        if (length <= Millis.ZERO) return 1f
        val x = Rules.unit(elapsed.value.toFloat() / length.value)
        return x * x * (SMOOTH_A - SMOOTH_B * x)
    }

    /** The palette [changes] palette changes after [own], wrapping round to the first. */
    fun palette(
        own: Int,
        changes: Int,
    ): Int = Math.floorMod(own + changes, PALETTES)

    /** The palettes of one frame of palette changes: [palette] fading into [next] by [mix]. */
    data class PaletteFrame(
        val palette: Int,
        val next: Int,
        val mix: Float,
    )

    /**
     * The palettes after [changes] changes from [own], [elapsed] into the last one's fade of [fade]
     * (null: none has happened). While it fades the old palette crossfades into the new one through
     * the second palette and its mix; once done the new palette shows alone. It never cuts.
     */
    fun paletteFrame(
        own: Int,
        changes: Int,
        elapsed: Millis?,
        fade: Millis,
    ): PaletteFrame {
        val now = palette(own, changes)
        if (changes <= 0 || elapsed == null) return PaletteFrame(now, now, 0f)
        val mix = crossfade(elapsed, fade)
        return if (mix >= 1f) PaletteFrame(now, now, 0f) else PaletteFrame(palette(own, changes - 1), now, mix)
    }

    /**
     * What [move] adds to each of its Camera properties (fractions of their ranges), [beats] beats into
     * the music: an eased swell, sin² over its span, so it starts and ends still and takes at least one
     * beat; its alternating properties swing the other way every other span. Nothing before the first beat.
     */
    fun camera(
        move: CameraMove,
        beats: Double,
    ): Map<SharedProperty, Float> {
        if (!(beats > 0.0)) return move.kind.moves.mapValues { 0f }
        val span = move.every.beats.toDouble()
        val round = floor(beats / span)
        val swell = sin(PI * (beats / span - round)).let { it * it }.toFloat()
        val odd = round.toLong() % 2 == 1L
        return move.kind.moves.mapValues { (property, direction) ->
            val sign = if (odd && property in move.kind.alternating) -1f else 1f
            direction * sign * move.strength * swell
        }
    }

    /** A Camera property whose own value is [base], moved by [offset]; both fractions of its range. */
    fun moved(
        base: Float,
        offset: Float,
    ): Float = Rules.unit(base + offset)

    /** The values [lane] holds its targets at on beat [beat], one per target in their order. */
    fun step(
        lane: Lane,
        beat: Int,
    ): List<Float> = lane.steps[Math.floorMod(beat / lane.step.beats, lane.steps.size)]

    private val PALETTES = SharedProperty.PALETTE.max.toInt() + 1
    private const val SEED_STRIDE = 1_000_003L
    private const val SMOOTH_A = 3f
    private const val SMOOTH_B = 2f
}
