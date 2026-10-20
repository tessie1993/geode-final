package dev.geode.feature.kernel.core

import dev.geode.engine.types.Millis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * The store's contract, through [Feature]: what it publishes, in which order it carries effects
 * out, and how timers behave. The reducer here is scripted: a command carries the effects it
 * should decide, and the state is the log of every event reduced so far.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeatureStoreTest {
    private val clock = FakeClock()
    private val timeline = mutableListOf<String>()
    private val runner = ScriptedRunner(timeline)
    private val store = storeOf(runner)

    @Test
    fun `the state starts as given and follows every command`() {
        assertEquals(emptyList<String>(), store.state.value)

        store.send(Command("a"))
        store.send(Command("b"))

        assertEquals(listOf("a", "b"), store.state.value)
    }

    @Test
    fun `work is handed to the runner in the order it was decided`() {
        store.send(Command("go", listOf(Perform("first"), Perform("second"))))

        assertEquals(listOf("first", "second"), runner.ran)
    }

    @Test
    fun `results come back through the reducer`() {
        runner.onRun = { work, report -> report(Result("$work done")) }

        store.send(Command("go", listOf(Perform("load"))))

        assertEquals(listOf("go", "load done"), store.state.value)
    }

    @Test
    fun `an event reported mid-transition waits until every effect of that transition has started`() {
        runner.onRun = { work, report -> report(Result("$work done")) }

        store.send(Command("go", listOf(Perform("a"), Perform("b"))))

        assertEquals(
            listOf("reduce go", "run a", "run b", "reduce a done", "reduce b done"),
            timeline,
        )
    }

    @Test
    fun `a command sent from an effect is reduced after the current transition`() {
        runner.onRun = { work, _ -> if (work == "x") store.send(Command("nested")) }

        store.send(Command("outer", listOf(Perform("x"), Perform("y"))))

        assertEquals(listOf("reduce outer", "run x", "run y", "reduce nested"), timeline)
    }

    @Test
    fun `facts are published to collectors and not replayed to late ones`() =
        runTest {
            val early = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.events.collect { early += it } }

            store.send(Command("go", listOf(Emit("started"), Emit("ready"))))
            val late = mutableListOf<String>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.events.collect { late += it } }
            store.send(Command("again", listOf(Emit("again"))))

            assertEquals(listOf("started", "ready", "again"), early)
            assertEquals(listOf("again"), late)
        }

    @Test
    fun `a timer delivers its event once its delay has passed, and only then`() {
        store.send(Command("arm", listOf(StartTimer("t", Millis(500), Result("tick")))))

        clock.advanceBy(Millis(499))
        assertEquals(listOf("arm"), store.state.value)

        clock.advanceBy(Millis(1))
        assertEquals(listOf("arm", "tick"), store.state.value)

        clock.advanceBy(Millis(10_000))
        assertEquals(listOf("arm", "tick"), store.state.value)
    }

    @Test
    fun `starting an armed timer again replaces it`() {
        store.send(Command("arm", listOf(StartTimer("t", Millis(500), Result("old")))))
        clock.advanceBy(Millis(300))
        store.send(Command("re-arm", listOf(StartTimer("t", Millis(500), Result("new")))))

        assertEquals(1, clock.pendingCount)
        clock.advanceBy(Millis(10_000))
        assertEquals(listOf("arm", "re-arm", "new"), store.state.value)
        assertEquals(Millis(10_300), clock.now)
    }

    @Test
    fun `timers with different keys run independently`() {
        store.send(
            Command(
                "arm",
                listOf(
                    StartTimer("slow", Millis(200), Result("slow")),
                    StartTimer("fast", Millis(100), Result("fast")),
                ),
            ),
        )
        store.send(Command("cancel fast", listOf(CancelTimer("fast"))))

        clock.advanceBy(Millis(1_000))

        assertEquals(listOf("arm", "cancel fast", "slow"), store.state.value)
    }

    @Test
    fun `a cancelled timer never fires and is released from the clock`() {
        store.send(Command("arm", listOf(StartTimer("t", Millis(500), Result("tick")))))
        store.send(Command("leave", listOf(CancelTimer("t"))))

        assertEquals(0, clock.pendingCount)
        clock.advanceBy(Millis(1_000))
        assertEquals(listOf("arm", "leave"), store.state.value)
    }

    @Test
    fun `cancelling a timer that is not armed does nothing`() {
        store.send(Command("leave", listOf(CancelTimer("never armed"))))

        assertEquals(listOf("leave"), store.state.value)
    }

    @Test
    fun `a timer that fired while its cancellation was being decided is not delivered`() {
        // The work stands in for something slow: the clock moves on while it runs, so the timer
        // fires before the store reaches the CancelTimer that follows it.
        runner.onRun = { _, _ -> clock.advanceBy(Millis(1_000)) }
        store.send(Command("arm", listOf(StartTimer("t", Millis(500), Result("tick")))))

        store.send(Command("leave", listOf(Perform("slow"), CancelTimer("t"))))

        assertEquals(listOf("arm", "leave"), store.state.value)
    }

    @Test
    fun `a timer that fired before it was replaced delivers only the replacement`() {
        runner.onRun = { _, _ -> clock.advanceBy(Millis(1_000)) }
        store.send(Command("arm", listOf(StartTimer("t", Millis(500), Result("old")))))

        store.send(Command("re-arm", listOf(Perform("slow"), StartTimer("t", Millis(500), Result("new")))))
        runner.onRun = { _, _ -> }
        clock.advanceBy(Millis(500))

        assertEquals(listOf("arm", "re-arm", "new"), store.state.value)
    }

    @Test
    fun `a reducer that throws fails the send but not the store`() {
        assertThrows(IllegalStateException::class.java) { store.send(Command(BOOM)) }

        store.send(Command("after"))

        assertEquals(listOf("after"), store.state.value)
    }

    @Test
    fun `commands sent from many threads are each reduced exactly once`() {
        val threads = 4
        val each = 500
        val start = CountDownLatch(1)
        val workers =
            List(threads) { n ->
                thread {
                    start.await()
                    repeat(each) { store.send(Command("$n")) }
                }
            }
        start.countDown()
        workers.forEach { it.join() }

        val counts = store.state.value.groupingBy { it }.eachCount()
        assertEquals((0 until threads).associate { "$it" to each }, counts)
    }

    private fun storeOf(runner: ScriptedRunner) =
        FeatureStore<List<String>, Probe, Command, String, String>(
            initial = emptyList(),
            reducer =
                Reducer { log, event ->
                    check(event.label != BOOM) { "scripted failure" }
                    timeline += "reduce ${event.label}"
                    Next(log + event.label, (event as? Command)?.effects.orEmpty())
                },
            runner = runner,
            timers = clock,
            scope = CoroutineScope(UnconfinedTestDispatcher()),
        )

    private sealed interface Probe {
        val label: String
    }

    private data class Command(
        override val label: String,
        val effects: List<Effect<String, Probe, String>> = emptyList(),
    ) : Probe

    private data class Result(
        override val label: String,
    ) : Probe

    private class ScriptedRunner(
        private val timeline: MutableList<String>,
    ) : EffectRunner<String, Probe> {
        val ran = mutableListOf<String>()
        var onRun: (String, (Probe) -> Unit) -> Unit = { _, _ -> }

        override suspend fun run(
            work: String,
            report: (Probe) -> Unit,
        ) {
            ran += work
            timeline += "run $work"
            onRun(work, report)
        }
    }

    private companion object {
        const val BOOM = "boom"
    }
}
