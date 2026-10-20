package dev.geode.feature.kernel.core

import dev.geode.engine.types.Millis
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The real scheduler and store scope, on the coroutine test scheduler's virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineTimerSchedulerTest {
    @Test
    fun `a scheduled action runs once its delay has passed`() =
        runTest {
            val scheduler = CoroutineTimerScheduler(backgroundScope)
            var calls = 0

            scheduler.schedule(Millis(500)) { calls++ }
            advanceTimeBy(499)
            runCurrent()
            assertEquals(0, calls)

            advanceTimeBy(1)
            runCurrent()
            assertEquals(1, calls)

            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1, calls)
        }

    @Test
    fun `a cancelled action never runs`() =
        runTest {
            val scheduler = CoroutineTimerScheduler(backgroundScope)
            var calls = 0

            val timer = scheduler.schedule(Millis(500)) { calls++ }
            advanceTimeBy(100)
            timer.cancel()
            timer.cancel()
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals(0, calls)
        }

    @Test
    fun `a store's timers fire through the real scheduler`() =
        runTest {
            val arm: List<Effect<Nothing, String, Nothing>> = listOf(StartTimer(1, Millis(250), "tick"))
            val store =
                FeatureStore<List<String>, String, String, Nothing, Nothing>(
                    initial = emptyList(),
                    reducer = { log, event -> Next(log + event, if (event == "arm") arm else emptyList()) },
                    runner = NoWork,
                    timers = CoroutineTimerScheduler(backgroundScope),
                    scope = backgroundScope,
                )

            store.send("arm")
            advanceTimeBy(251)
            runCurrent()

            assertEquals(listOf("arm", "tick"), store.state.value)
        }

    @Test
    fun `a failing effect goes to the failure handler and the scope carries on`() =
        runTest {
            val failures = mutableListOf<String?>()
            val scope = featureScope(StandardTestDispatcher(testScheduler)) { failures += it.message }
            var sibling = false

            scope.launch { error("effect failed") }
            scope.launch { sibling = true }
            runCurrent()

            assertEquals(listOf<String?>("effect failed"), failures)
            assertTrue(sibling)
        }

    private object NoWork : EffectRunner<Nothing, String> {
        override suspend fun run(
            work: Nothing,
            report: (String) -> Unit,
        ) = Unit
    }
}
