package dev.geode.kernel

import dev.geode.engine.types.Millis
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** What the stores share: the real timer scheduler, the error channel and the runtime's scope. */
@OptIn(ExperimentalCoroutinesApi::class)
class FeatureRuntimeTest {
    @Test
    fun `a scheduled action runs on the executor once its delay has passed`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val ran = CountDownLatch(1)

        ExecutorTimerScheduler(executor).schedule(Millis(20)) { ran.countDown() }

        assertTrue(ran.await(5, TimeUnit.SECONDS))
        executor.shutdownNow()
    }

    @Test
    fun `a cancelled action never runs`() {
        val executor = Executors.newSingleThreadScheduledExecutor()
        val ran = CountDownLatch(1)

        val timer = ExecutorTimerScheduler(executor).schedule(Millis(200)) { ran.countDown() }
        timer.cancel()
        timer.cancel()

        assertFalse(ran.await(500, TimeUnit.MILLISECONDS))
        executor.shutdownNow()
    }

    @Test
    fun `a report is logged before it is published, and published to whoever collects`() =
        runTest {
            val order = mutableListOf<String>()
            val errors = ErrorChannel { order += "logged ${it.message}" }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { errors.failures.collect { order += "shown ${it.message}" } }

            errors.report(FeatureFailure("capture", "lost"))

            assertEquals(listOf("logged lost", "shown lost"), order)
        }

    @Test
    fun `work that throws in the runtime's scope is reported under its name, and its siblings carry on`() =
        runTest {
            val failures = mutableListOf<FeatureFailure>()
            val background = StandardTestDispatcher(testScheduler)
            val runtime = FeatureRuntime(background, background, FakeClock(), ErrorChannel { failures += it })
            var sibling = false

            runtime.scope.launch(background + CoroutineName("library")) { error("scan failed") }
            runtime.scope.launch(background) { error("no name") }
            runtime.scope.launch(background) { sibling = true }
            runCurrent()

            assertEquals(listOf("library", UNNAMED), failures.map { it.feature })
            assertEquals("java.lang.IllegalStateException: scan failed", failures.first().message)
            assertEquals("scan failed", failures.first().cause?.message)
            assertTrue(sibling)
        }
}
