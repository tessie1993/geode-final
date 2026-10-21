package dev.geode.kernel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * A [FeatureRuntime] for tests in which reducers and handlers run in place, on the calling thread, so
 * a test sees the result of a send as soon as it returns. Time is [timers]; every failure reported to
 * the error channel is added to [failures].
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun inPlaceRuntime(
    timers: TimerScheduler = FakeClock(),
    failures: MutableList<FeatureFailure> = mutableListOf(),
): FeatureRuntime {
    val inPlace = UnconfinedTestDispatcher()
    return FeatureRuntime(main = inPlace, background = inPlace, timers = timers, errors = ErrorChannel { failures += it })
}
