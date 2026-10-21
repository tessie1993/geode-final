package dev.geode.kernel.sample.handlers

import dev.geode.audio.adapter.AudioAdapter
import dev.geode.audio.adapter.CaptureAnnouncement
import dev.geode.audio.adapter.CaptureFailure
import dev.geode.audio.adapter.CaptureSource
import dev.geode.audio.adapter.FakeAudioAdapter
import dev.geode.engine.types.Millis
import dev.geode.kernel.FakeClock
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import dev.geode.kernel.sample.api.ListenCommand
import dev.geode.kernel.sample.api.ListenFact
import dev.geode.kernel.sample.api.ListenProblem
import dev.geode.kernel.sample.api.ListenSource
import dev.geode.kernel.sample.api.ListenState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The whole loop with the sample feature: commands in, effects carried out against the audio
 * adapter's fake, its announcements back through the reducer, a timer that lives as long as one
 * state, a fact out, and a failure on the app-wide error channel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListenStoreTest {
    private val audio = FakeAudioAdapter()
    private val clock = FakeClock()
    private val failures = mutableListOf<FeatureFailure>()
    private val store = listenStore(audio, inPlaceRuntime(clock, failures))

    @Test
    fun `starting listens once the adapter announces the capture running`() {
        store.send(ListenCommand.Start(ListenSource.LINE_IN))

        assertEquals(ListenState.Listening(ListenSource.LINE_IN, quiet = false), store.state.value)
        assertEquals(listOf("startCapture(LINE_IN)"), audio.calls)
        assertEquals(0, clock.pendingCount)
    }

    @Test
    fun `the adapter's announcements keep the state up to date`() {
        store.send(ListenCommand.Start(ListenSource.MICROPHONE))

        audio.announce(CaptureAnnouncement.QuietChanged(quiet = true))

        assertEquals(ListenState.Listening(ListenSource.MICROPHONE, quiet = true), store.state.value)
    }

    @Test
    fun `an unplugged capture is reported once as lost`() =
        runTest {
            val facts = mutableListOf<ListenFact>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.events.collect { facts += it } }
            store.send(ListenCommand.Start(ListenSource.LINE_IN))

            audio.announce(CaptureAnnouncement.Lost(CaptureSource.LINE_IN))
            audio.announce(CaptureAnnouncement.Lost(CaptureSource.LINE_IN))

            assertEquals(ListenState.Off, store.state.value)
            assertEquals(listOf(ListenFact.Lost), facts)
        }

    @Test
    fun `a refused start leaves the feature failed with the adapter's reason`() {
        audio.refuseNextStart = CaptureFailure.PERMISSION

        store.send(ListenCommand.Start(ListenSource.MICROPHONE))

        assertEquals(ListenState.Failed(ListenProblem.PERMISSION), store.state.value)
        assertEquals(0, clock.pendingCount)
    }

    @Test
    fun `a start that never runs times out, is reported, and is undone`() {
        val stuck = listenStore(SilentStarts(audio), inPlaceRuntime(clock, failures))

        stuck.send(ListenCommand.Start(ListenSource.MICROPHONE))
        clock.advanceBy(Millis(1_999))
        assertEquals(ListenState.Starting(ListenSource.MICROPHONE), stuck.state.value)
        clock.advanceBy(Millis(1))

        assertEquals(ListenState.Failed(ListenProblem.DID_NOT_START), stuck.state.value)
        assertEquals(listOf("listen: capture from MICROPHONE did not start"), failures.map { "${it.feature}: ${it.message}" })
        assertEquals(listOf("stopCapture"), audio.calls)
    }

    @Test
    fun `stopping ends the capture`() {
        store.send(ListenCommand.Start(ListenSource.MICROPHONE))

        store.send(ListenCommand.Stop)

        assertEquals(ListenState.Off, store.state.value)
        assertEquals(listOf("startCapture(MICROPHONE)", "stopCapture"), audio.calls)
    }

    /** An adapter whose starts are accepted but never announced, as with a device that hangs. */
    private class SilentStarts(
        private val audio: FakeAudioAdapter,
    ) : AudioAdapter by audio {
        override suspend fun startCapture(source: CaptureSource): CaptureFailure? = null
    }
}
