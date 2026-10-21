package dev.geode.kernel

import dev.geode.engine.types.Millis
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Runs an action after a delay. Injected into the stores so tests can drive time with a fake clock. */
interface TimerScheduler {
    /** Runs [action] once, [after] from now, unless the returned timer is cancelled first. */
    fun schedule(
        after: Millis,
        action: Runnable,
    ): ScheduledTimer
}

/** A pending action from a [TimerScheduler]. */
fun interface ScheduledTimer {
    /** Stops the action if it has not run yet. Safe to call more than once. */
    fun cancel()
}

/**
 * The real [TimerScheduler], on [executor]'s thread. A fired timer only hands its event to the store,
 * which reduces it on the main thread.
 */
class ExecutorTimerScheduler(
    private val executor: ScheduledExecutorService,
) : TimerScheduler {
    override fun schedule(
        after: Millis,
        action: Runnable,
    ): ScheduledTimer {
        val pending = executor.schedule(action, after.value, TimeUnit.MILLISECONDS)
        return ScheduledTimer { pending.cancel(false) }
    }
}
