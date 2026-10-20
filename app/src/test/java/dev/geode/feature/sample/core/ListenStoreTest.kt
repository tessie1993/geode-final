package dev.geode.feature.sample.core

import dev.geode.audio.adapter.CaptureFailure
import dev.geode.audio.adapter.CaptureSource
import dev.geode.audio.adapter.FakeAudioAdapter
import dev.geode.engine.types.Millis
import dev.geode.kernel.FakeClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The whole loop with the sample feature: commands in, effects against the audio adapter's fake,
 * results back through the reducer, a timer that lives as long as one state, and a fact out.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListenStoreTest {
    private val audio = FakeAudioAdapter()
    private val clock = FakeClock()

    @Test
    fun `starting captures from the source and checks on it while listening`() =
        runTest {
            val store = listenStore(audio, clock, backgroundScope)

            store.send(ListenCommand.Start(CaptureSource.LINE_IN))
            assertEquals(ListenState.Listening(CaptureSource.LINE_IN, quiet = false), store.state.value)

            audio.hearSilence()
            clock.advanceBy(Millis(500))
            assertEquals(ListenState.Listening(CaptureSource.LINE_IN, quiet = true), store.state.value)
            assertEquals(1, clock.pendingCount)
            assertEquals(listOf("startCapture(LINE_IN)"), audio.calls)
        }

    @Test
    fun `stopping ends the capture and the check timer with it`() =
        runTest {
            val store = listenStore(audio, clock, backgroundScope)
            store.send(ListenCommand.Start(CaptureSource.MICROPHONE))

            store.send(ListenCommand.Stop)

            assertEquals(ListenState.Off, store.state.value)
            assertEquals(0, clock.pendingCount)
            assertEquals(listOf("startCapture(MICROPHONE)", "stopCapture"), audio.calls)
        }

    @Test
    fun `a refused start leaves the feature failed with the adapter's reason`() =
        runTest {
            val store = listenStore(audio, clock, backgroundScope)
            audio.refuseNextStart = CaptureFailure.PERMISSION

            store.send(ListenCommand.Start(CaptureSource.MICROPHONE))

            assertEquals(ListenState.Failed(CaptureFailure.PERMISSION), store.state.value)
            assertEquals(0, clock.pendingCount)
        }

    @Test
    fun `a capture that ends by itself is noticed at the next check and reported once`() =
        runTest {
            val store = listenStore(audio, clock, backgroundScope)
            val facts = mutableListOf<ListenFact>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.events.collect { facts += it } }
            store.send(ListenCommand.Start(CaptureSource.LINE_IN))

            audio.unplug()
            clock.advanceBy(Millis(499))
            assertEquals(ListenState.Listening(CaptureSource.LINE_IN, quiet = false), store.state.value)
            clock.advanceBy(Millis(1))

            assertEquals(ListenState.Off, store.state.value)
            assertEquals(listOf(ListenFact.Lost), facts)
            clock.advanceBy(Millis(5_000))
            assertEquals(listOf(ListenFact.Lost), facts)
        }
}
