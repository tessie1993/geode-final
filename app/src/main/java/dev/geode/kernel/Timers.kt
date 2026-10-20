package dev.geode.kernel

import dev.geode.engine.types.Millis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Runs an action after a delay. Injected into a store so tests can drive time with a fake clock. */
interface TimerScheduler {
    /** Calls [action] once, [after] from now, unless the returned timer is cancelled first. */
    fun schedule(
        after: Millis,
        action: () -> Unit,
    ): ScheduledTimer
}

/** A pending call from a [TimerScheduler]. */
fun interface ScheduledTimer {
    /** Stops the call if it has not happened yet. Safe to call more than once. */
    fun cancel()
}

/** The real [TimerScheduler]: a coroutine per timer in [scope], on the scope's dispatcher. */
class CoroutineTimerScheduler(
    private val scope: CoroutineScope,
) : TimerScheduler {
    override fun schedule(
        after: Millis,
        action: () -> Unit,
    ): ScheduledTimer {
        val job =
            scope.launch {
                delay(after.value)
                action()
            }
        return ScheduledTimer { job.cancel() }
    }
}
