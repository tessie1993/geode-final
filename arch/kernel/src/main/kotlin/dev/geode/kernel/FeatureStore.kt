package dev.geode.kernel

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.EmptyCoroutineContext

/**
 * A feature as everyone outside it sees it, the shape of every feature's `:api` (ADR-0003, ADR-0005):
 * a read-only [state], [send] for commands, and [events] of one-off facts the feature owns. Features
 * talk to each other only through this.
 */
interface Feature<out S, in C, out O> {
    /** Immutable; a new value is published only when it differs from the last (StateFlow is distinct). */
    val state: StateFlow<S>

    /**
     * One-off facts, such as "the capture was lost". Not replayed: a collector sees only what is
     * emitted while it collects. Up to [EVENT_BUFFER] facts wait for a slow collector, after which
     * the oldest are dropped.
     */
    val events: Flow<O>

    fun send(command: C)
}

/** How many events a slow collector may fall behind before the oldest are dropped. */
const val EVENT_BUFFER = 64

/**
 * The one app-scoped store of a feature, named [name]: it holds the state, runs every event through
 * [reducer] and carries out the effects.
 *
 * Commands ([C], from the feature's `:api`) become reducer events ([E], from its `:core`) through
 * [toEvent]. Results from [runner] and fired timers are events too, and all go through the reducer.
 *
 * Ordering. Events are reduced one at a time, in the order they arrive. For each one the new state is
 * published, then its effects are carried out in list order, before the next event is reduced. An
 * event that arrives while another is being reduced waits its turn instead of nesting.
 *
 * Threading (ADR-0005). The reducer runs on the [runtime]'s main dispatcher: in place when the event
 * arrives there, otherwise posted to it. [Perform] work runs on the runtime's background dispatcher,
 * named after the feature, so work that throws reaches the app-wide [ErrorChannel]. A reducer that
 * throws is reported there too, and the store goes on with the next event.
 *
 * [start] is carried out once, when the store is built, before any event: the place to subscribe to
 * what the feature listens to, such as the audio adapter's announcements.
 */
class FeatureStore<S, C, E, W, O>(
    private val name: String,
    initial: S,
    private val reducer: Reducer<S, E, Effect<W, E, O>>,
    private val toEvent: (C) -> E,
    private val runner: EffectRunner<W, E>,
    private val runtime: FeatureRuntime,
    start: List<Effect<W, E, O>> = emptyList(),
) : Feature<S, C, O> {
    private val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<S> = mutableState.asStateFlow()

    private val mutableEvents =
        MutableSharedFlow<O>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events: Flow<O> = mutableEvents.asSharedFlow()

    private val results = Results<E> { deliver(Sent(it)) }

    private val lock = Any()

    // Guarded by lock.
    private val inbox = ArrayDeque<Delivery<E>>()
    private var draining = false

    // Touched only while draining, which the lock hands from one drain to the next.
    private val armed = HashMap<Any, Fired<E>>()

    init {
        start.forEach(::carryOut)
    }

    override fun send(command: C) = deliver(Sent(toEvent(command)))

    private fun deliver(delivery: Delivery<E>) {
        synchronized(lock) {
            inbox.addLast(delivery)
            if (draining) return
            draining = true
        }
        val main = runtime.main
        if (main.isDispatchNeeded(EmptyCoroutineContext)) {
            main.dispatch(EmptyCoroutineContext, Runnable { drain() })
        } else {
            drain()
        }
    }

    private fun drain() {
        while (true) {
            val delivery = take() ?: return
            runCatching { step(delivery) }
                .onFailure { runtime.errors.report(FeatureFailure(name, it.toString(), it)) }
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
            is Perform -> perform(effect.work)
            is StartTimer -> arm(effect)
            is CancelTimer -> armed.remove(effect.key)?.timer?.cancel()
            is Emit -> mutableEvents.tryEmit(effect.event)
            is ReportFailure -> runtime.errors.report(FeatureFailure(name, effect.message))
        }
    }

    private fun perform(work: W) {
        runtime.scope.launch(runtime.background + CoroutineName(name)) { runner.run(work, results) }
    }

    private fun arm(effect: StartTimer<E>) {
        armed.remove(effect.key)?.timer?.cancel()
        val fired = Fired(effect.key, effect.fire)
        armed[effect.key] = fired
        fired.timer = runtime.timers.schedule(effect.after) { deliver(fired) }
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
