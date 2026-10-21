package dev.geode.kernel

import dev.geode.engine.types.Millis

/**
 * A [TimerScheduler] whose time moves only when a test calls [advanceBy]. Due timers fire in time
 * order (and in the order they were scheduled when due together), on the calling thread.
 */
class FakeClock : TimerScheduler {
    var now: Millis = Millis.ZERO
        private set

    private var nextSequence = 0L
    private val pending = mutableListOf<Pending>()

    /** Timers scheduled and neither fired nor cancelled. */
    val pendingCount: Int get() = pending.size

    override fun schedule(
        after: Millis,
        action: Runnable,
    ): ScheduledTimer {
        val entry = Pending(now + after, nextSequence++, action)
        pending += entry
        return ScheduledTimer { pending.remove(entry) }
    }

    fun advanceBy(duration: Millis) {
        val target = now + duration
        while (true) {
            val due =
                pending
                    .filter { it.at <= target }
                    .minWithOrNull(compareBy<Pending> { it.at.value }.thenBy { it.sequence })
                    ?: break
            pending.remove(due)
            now = due.at
            due.action.run()
        }
        now = target
    }

    private class Pending(
        val at: Millis,
        val sequence: Long,
        val action: Runnable,
    )
}
