package dev.geode.kernel

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * A feature as everyone outside it sees it (ADR-0003): a read-only [state], [send] for commands,
 * and [events] of one-off facts the feature owns. Features talk to each other only through this.
 */
interface Feature<out S, in C, out O> {
    val state: StateFlow<S>

    /**
     * One-off facts, such as "the capture was lost". Not replayed: a collector sees only what is
     * emitted while it collects. Up to [EVENT_BUFFER] facts wait for a slow collector, after which
     * the oldest are dropped.
     */
    val events: Flow<O>

    fun send(command: C)
}

/** How many [Feature.events] a slow collector may fall behind before the oldest are dropped. */
const val EVENT_BUFFER = 64

/**
 * The one app-scoped store of a feature: it holds the state, runs every event through [reducer]
 * and carries out the effects.
 *
 * Commands ([C]) are the feature's public events; results from [runner] and fired timers are
 * events ([E]) too, and all of them go through the same reducer.
 *
 * Ordering. Events are reduced one at a time, in the order they arrive. For each one the new state
 * is published, then its effects are carried out in list order, before the next event is reduced.
 * An event delivered while another is being reduced (a [send] from an effect, a result reported
 * synchronously) waits its turn instead of nesting.
 *
 * Threading. [send] may be called from any thread. The reducer runs on whichever thread is
 * delivering at the time, never on two at once. [Perform] work starts undispatched in [scope];
 * timers fire through [timers].
 */
class FeatureStore<S, E, C : E, W, O>(
    initial: S,
    private val reducer: Reducer<S, E, Effect<W, E, O>>,
    private val runner: EffectRunner<W, E>,
    private val timers: TimerScheduler,
    private val scope: CoroutineScope,
) : Feature<S, C, O> {
    private val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<S> = mutableState.asStateFlow()

    private val mutableEvents =
        MutableSharedFlow<O>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<O> = mutableEvents.asSharedFlow()

    private val lock = Any()

    // Guarded by lock.
    private val inbox = ArrayDeque<Delivery<E>>()
    private var draining = false

    // Touched only by the delivering thread, which the lock hands from one to the next.
    private val armed = HashMap<Any, Fired<E>>()

    override fun send(command: C) = deliver(Sent<E>(command))

    private fun deliver(delivery: Delivery<E>) {
        synchronized(lock) {
            inbox.addLast(delivery)
            if (draining) return
            draining = true
        }
        var next = take()
        try {
            while (next != null) {
                step(next)
                next = take()
            }
        } finally {
            // Only a throwing reducer leaves here with work in hand; let the next delivery drain.
            if (next != null) synchronized(lock) { draining = false }
        }
    }

    private fun take(): Delivery<E>? =
        synchronized(lock) {
            inbox.removeFirstOrNull().also { if (it == null) draining = false }
        }

    private fun step(delivery: Delivery<E>) {
        val event =
            when (delivery) {
                is Sent -> delivery.event
                is Fired -> {
                    if (armed[delivery.key] !== delivery) return
                    armed.remove(delivery.key)
                    delivery.event
                }
            }
        val next = reducer.reduce(mutableState.value, event)
        mutableState.value = next.state
        next.effects.forEach(::carryOut)
    }

    private fun carryOut(effect: Effect<W, E, O>) {
        when (effect) {
            is Perform -> scope.launch(start = CoroutineStart.UNDISPATCHED) { runner.run(effect.work) { deliver(Sent(it)) } }
            is StartTimer -> arm(effect)
            is CancelTimer -> armed.remove(effect.key)?.timer?.cancel()
            is Emit -> mutableEvents.tryEmit(effect.event)
        }
    }

    private fun arm(effect: StartTimer<E>) {
        armed.remove(effect.key)?.timer?.cancel()
        val fired = Fired(effect.key, effect.fire)
        armed[effect.key] = fired
        fired.timer = timers.schedule(effect.after) { deliver(fired) }
    }

    private sealed interface Delivery<E>

    private class Sent<E>(
        val event: E,
    ) : Delivery<E>

    private class Fired<E>(
        val key: Any,
        val event: E,
    ) : Delivery<E> {
        var timer: ScheduledTimer? = null
    }
}

/**
 * The scope a feature store runs its work in: a failing effect is handed to [onFailure] and does
 * not cancel the store's other work.
 */
fun featureScope(
    context: CoroutineContext,
    onFailure: (Throwable) -> Unit,
): CoroutineScope = CoroutineScope(SupervisorJob() + context + CoroutineExceptionHandler { _, failure -> onFailure(failure) })
