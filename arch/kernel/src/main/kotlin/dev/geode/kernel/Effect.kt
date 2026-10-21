package dev.geode.kernel

import dev.geode.engine.types.Millis

/**
 * An effect a reducer asks its store to carry out. Plain data, so a transition-table test compares
 * effects with `==`.
 *
 * [W] is the feature's own work, carried out by its [EffectRunner]. [E] is the feature's event type,
 * which a timer delivers when it fires. [O] is the feature's outward event type, see [Feature.events].
 */
sealed interface Effect<out W, out E, out O>

/** Feature work for the [EffectRunner], whose results come back as events. */
data class Perform<out W>(
    val work: W,
) : Effect<W, Nothing, Nothing>

/**
 * Arms the timer named [key]: after [after], [fire] is delivered to the reducer.
 *
 * Starting a key that is already armed replaces it. Timers fire once; a reducer that wants a
 * periodic tick starts the timer again when it handles [fire]. The usual shape is to start a timer
 * on entering a state and [CancelTimer] it on leaving.
 */
data class StartTimer<out E>(
    val key: Any,
    val after: Millis,
    val fire: E,
) : Effect<Nothing, E, Nothing>

/**
 * Disarms the timer named [key]. Its event is never delivered after this effect, even if the timer
 * had already fired and its event was waiting in the store's queue. Cancelling a key that is not
 * armed does nothing.
 */
data class CancelTimer(
    val key: Any,
) : Effect<Nothing, Nothing, Nothing>

/** Publishes [event] on the feature's [Feature.events]. */
data class Emit<out O>(
    val event: O,
) : Effect<Nothing, Nothing, O>

/**
 * Reports a failure the reducer has decided on, such as "the capture did not start", to the app-wide
 * [ErrorChannel] under the feature's name (ADR-0004).
 */
data class ReportFailure(
    val message: String,
) : Effect<Nothing, Nothing, Nothing>
