package dev.geode.kernel

import dev.geode.engine.types.Millis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * The store's contract, through [Feature]: what it publishes, in which order it carries effects out,
 * how timers behave and where failures go. The reducer here is scripted: a command carries the effects
 * it should decide, and the state is the log of every event reduced so far.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FeatureStoreTest {
    private val clock = FakeClock()
    private val failures = mutableListOf<FeatureFailure>()
    private val timeline = mutableListOf<String>()
    private val runner = ScriptedRunner(timeline)
    private val store = storeOf(inPlaceRuntime(clock, failures))

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
        runner.onRun = { work, results -> results.report(Result("$work done")) }

        store.send(Command("go", listOf(Perform("load"))))

        assertEquals(listOf("go", "load done"), store.state.value)
    }

    @Test
    fun `a result reported mid-transition waits until every effect of that transition has started`() {
        runner.onRun = { work, results -> results.report(Result("$work done")) }

        store.send(Command("go", listOf(Perform("a"), Perform("b"))))

        assertEquals(listOf("reduce go", "run a", "run b", "reduce a done", "reduce b done"), timeline)
    }

    @Test
    fun `a command sent from an effect is reduced after the current transition`() {
        runner.onRun = { work, _ -> if (work == "x") store.send(Command("nested")) }

        store.send(Command("outer", listOf(Perform("x"), Perform("y"))))

        assertEquals(listOf("reduce outer", "run x", "run y", "reduce nested"), timeline)
    }

    @Test
    fun `work that suspends reports when it resumes`() {
        val gate = CompletableDeferred<String>()
        runner.onSuspendingRun = { results -> results.report(Result(gate.await())) }

        store.send(Command("go", listOf(Perform("wait"))))
        assertEquals(listOf("go"), store.state.value)
        gate.complete("arrived")

        assertEquals(listOf("go", "arrived"), store.state.value)
        assertEquals(emptyList<FeatureFailure>(), failures)
    }

    @Test
    fun `work that throws is reported under the feature's name and other work carries on`() {
        runner.onRun = { work, results -> if (work == "bad") error("disk full") else results.report(Result("$work done")) }

        store.send(Command("go", listOf(Perform("bad"), Perform("good"))))

        assertEquals(listOf("go", "good done"), store.state.value)
        val failure = failures.single()
        assertEquals("probe", failure.feature)
        assertEquals("java.lang.IllegalStateException: disk full", failure.message)
        assertEquals("disk full", failure.cause?.message)
    }

    @Test
    fun `a reducer that throws is reported and the store goes on with the next event`() {
        store.send(Command(BOOM))
        store.send(Command("after"))

        assertEquals(listOf("after"), store.state.value)
        assertEquals("probe", failures.single().feature)
        assertEquals("scripted failure", failures.single().cause?.message)
    }

    @Test
    fun `a failure the reducer decides on goes to the error channel`() {
        store.send(Command("go", listOf(ReportFailure("capture did not start"))))

        assertEquals(listOf("probe: capture did not start"), failures.map { "${it.feature}: ${it.message}" })
        assertEquals(null, failures.single().cause)
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
    fun `start effects are carried out when the store is built`() {
        val started = storeOf(inPlaceRuntime(clock, failures), start = listOf(Perform("subscribe")))

        assertEquals(listOf("subscribe"), runner.ran)
        assertEquals(emptyList<String>(), started.state.value)
    }

    @Test
    fun `an event that arrives off the main dispatcher is reduced there`() =
        runTest {
            val main = StandardTestDispatcher(testScheduler)
            val runtime = FeatureRuntime(main, main, clock, ErrorChannel { failures += it })
            val onMain = storeOf(runtime)

            onMain.send(Command("tap"))
            assertEquals(emptyList<String>(), onMain.state.value)
            runCurrent()

            assertEquals(listOf("tap"), onMain.state.value)
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
    }

    @Test
    fun `timers with different keys run independently`() {
        val both = listOf(StartTimer("slow", Millis(200), Result("slow")), StartTimer("fast", Millis(100), Result("fast")))
        store.send(Command("arm", both))
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
        store.send(Command("arm", listOf(StartTimer("t", Millis(500), Result("tick")))))

        // Time moves on while the reducer decides, so the timer fires before the CancelTimer runs.
        store.send(Command("leave", listOf(CancelTimer("t")), whileReducing = { clock.advanceBy(Millis(1_000)) }))

        assertEquals(listOf("arm", "leave"), store.state.value)
    }

    @Test
    fun `a timer that fired before it was replaced delivers only the replacement`() {
        store.send(Command("arm", listOf(StartTimer("t", Millis(500), Result("old")))))

        val replace = listOf(StartTimer("t", Millis(500), Result("new")))
        store.send(Command("re-arm", replace, whileReducing = { clock.advanceBy(Millis(1_000)) }))
        clock.advanceBy(Millis(500))

        assertEquals(listOf("arm", "re-arm", "new"), store.state.value)
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

        val counts =
            store.state.value
                .groupingBy { it }
                .eachCount()
        assertEquals((0 until threads).associate { "$it" to each }, counts)
    }

    @Test
    fun `the store hands commands to the reducer through its mapping`() {
        val mapped =
            FeatureStore<List<String>, String, Probe, String, String>(
                name = "mapped",
                initial = emptyList(),
                reducer = { log, event -> Next(log + event.label) },
                toEvent = { Command("cmd:$it") },
                runner = runner,
                runtime = inPlaceRuntime(clock, failures),
            )

        mapped.send("play")

        assertEquals(listOf("cmd:play"), mapped.state.value)
    }

    private fun storeOf(
        runtime: FeatureRuntime,
        start: List<Effect<String, Probe, String>> = emptyList(),
    ) = FeatureStore<List<String>, Command, Probe, String, String>(
        name = "probe",
        initial = emptyList(),
        reducer =
            Reducer { log, event ->
                check(event.label != BOOM) { "scripted failure" }
                timeline += "reduce ${event.label}"
                (event as? Command)?.whileReducing?.invoke()
                Next(log + event.label, (event as? Command)?.effects.orEmpty())
            },
        toEvent = { it },
        runner = runner,
        runtime = runtime,
        start = start,
    )

    private sealed interface Probe {
        val label: String
    }

    private class Command(
        override val label: String,
        val effects: List<Effect<String, Probe, String>> = emptyList(),
        val whileReducing: () -> Unit = {},
    ) : Probe

    private class Result(
        override val label: String,
    ) : Probe

    private class ScriptedRunner(
        private val timeline: MutableList<String>,
    ) : EffectRunner<String, Probe> {
        val ran = mutableListOf<String>()
        var onRun: (String, Results<Probe>) -> Unit = { _, _ -> }
        var onSuspendingRun: (suspend (Results<Probe>) -> Unit)? = null

        override suspend fun run(
            work: String,
            results: Results<Probe>,
        ) {
            ran += work
            timeline += "run $work"
            val suspending = onSuspendingRun
            if (suspending != null) suspending(results) else onRun(work, results)
        }
    }

    private companion object {
        const val BOOM = "boom"
    }
}
