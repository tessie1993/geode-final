package dev.geode.feature.capture.handlers

import dev.geode.audio.adapter.AudioAdapter
import dev.geode.audio.adapter.CaptureAnnouncement
import dev.geode.audio.adapter.CaptureFailure
import dev.geode.audio.adapter.FakeAudioAdapter
import dev.geode.engine.types.Millis
import dev.geode.feature.capture.api.CaptureCommand
import dev.geode.feature.capture.api.CaptureFact
import dev.geode.feature.capture.api.CaptureProblem
import dev.geode.feature.capture.api.CaptureSource
import dev.geode.feature.capture.api.CaptureState
import dev.geode.feature.capture.api.MusicChoice
import dev.geode.feature.capture.core.MusicControl
import dev.geode.feature.capture.core.START_TIMEOUT
import dev.geode.kernel.FakeClock
import dev.geode.kernel.FeatureFailure
import dev.geode.kernel.inPlaceRuntime
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import dev.geode.audio.adapter.CaptureSource as AdapterSource

/**
 * Capture end to end through its store: commands in, the handlers against the audio adapter's fake and
 * a fake Playback, announcements back through the reducer, the start timeout on a fake clock, facts
 * out, and failures on the app-wide error channel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CaptureStoreTest {
    private val audio = FakeAudioAdapter()
    private val music = FakeMusic()
    private val clock = FakeClock()
    private val failures = mutableListOf<FeatureFailure>()
    private val store = captureStore(audio, music, inPlaceRuntime(clock, failures))

    @Test
    fun `with no song playing the microphone starts at once`() {
        store.send(CaptureCommand.Start(CaptureSource.MICROPHONE))

        assertEquals(CaptureState.Capturing(CaptureSource.MICROPHONE, quiet = false), store.state.value)
        assertEquals(listOf("startCapture(MICROPHONE)"), audio.calls)
        assertEquals(0, music.pauses)
        assertEquals(0, clock.pendingCount)
    }

    @Test
    fun `with a song playing the person is asked, and pausing it waits until capture runs`() {
        music.playing = true

        store.send(CaptureCommand.Start(CaptureSource.MICROPHONE))
        assertEquals(CaptureState.AskingAboutMusic(CaptureSource.MICROPHONE), store.state.value)
        assertEquals(emptyList<String>(), audio.calls)

        store.send(CaptureCommand.ChooseMusic(MusicChoice.PAUSE))

        assertEquals(CaptureState.Capturing(CaptureSource.MICROPHONE, quiet = false), store.state.value)
        assertEquals(1, music.pauses)
    }

    @Test
    fun `keeping the song playing leaves it alone`() {
        music.playing = true

        store.send(CaptureCommand.Start(CaptureSource.MICROPHONE))
        store.send(CaptureCommand.ChooseMusic(MusicChoice.KEEP_PLAYING))

        assertEquals(CaptureState.Capturing(CaptureSource.MICROPHONE, quiet = false), store.state.value)
        assertEquals(0, music.pauses)
    }

    @Test
    fun `a missing permission is asked for and the start is tried again once granted`() {
        music.playing = true
        audio.refuseNextStart = CaptureFailure.PERMISSION

        store.send(CaptureCommand.Start(CaptureSource.MICROPHONE))
        store.send(CaptureCommand.ChooseMusic(MusicChoice.PAUSE))
        assertEquals(CaptureState.AwaitingPermission(CaptureSource.MICROPHONE, MusicChoice.PAUSE), store.state.value)
        assertEquals(0, music.pauses)

        store.send(CaptureCommand.PermissionAnswered(granted = true))

        assertEquals(CaptureState.Capturing(CaptureSource.MICROPHONE, quiet = false), store.state.value)
        assertEquals(listOf("startCapture(MICROPHONE)", "startCapture(MICROPHONE)"), audio.calls)
        assertEquals(1, music.pauses)
    }

    @Test
    fun `a refused permission is explained and capture stops`() {
        audio.refuseNextStart = CaptureFailure.PERMISSION

        store.send(CaptureCommand.Start(CaptureSource.MICROPHONE))
        store.send(CaptureCommand.PermissionAnswered(granted = false))

        assertEquals(CaptureState.Failed(CaptureSource.MICROPHONE, CaptureProblem.PERMISSION_DENIED), store.state.value)
        assertEquals(0, clock.pendingCount)
    }

    @Test
    fun `line-in with nothing plugged in is explained, with no fallback to the microphone`() {
        audio.sources = setOf(AdapterSource.MICROPHONE)

        store.send(CaptureCommand.Start(CaptureSource.LINE_IN))

        assertEquals(CaptureState.Failed(CaptureSource.LINE_IN, CaptureProblem.SOURCE_UNAVAILABLE), store.state.value)
        assertEquals(listOf("startCapture(LINE_IN)"), audio.calls)
    }

    @Test
    fun `an unplugged interface stops the capture and is reported once as lost`() =
        runTest {
            val facts = mutableListOf<CaptureFact>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { store.events.collect { facts += it } }
            store.send(CaptureCommand.Start(CaptureSource.LINE_IN))

            audio.announce(CaptureAnnouncement.Lost(AdapterSource.LINE_IN))
            audio.announce(CaptureAnnouncement.Lost(AdapterSource.LINE_IN))

            assertEquals(CaptureState.Failed(CaptureSource.LINE_IN, CaptureProblem.SOURCE_LOST), store.state.value)
            assertEquals(listOf(CaptureFact.Lost(CaptureSource.LINE_IN)), facts)
        }

    @Test
    fun `the adapter's quiet announcements reach the state`() {
        store.send(CaptureCommand.Start(CaptureSource.MICROPHONE))

        audio.announce(CaptureAnnouncement.QuietChanged(quiet = true))

        assertEquals(CaptureState.Capturing(CaptureSource.MICROPHONE, quiet = true), store.state.value)
    }

    @Test
    fun `a start that never runs times out, is reported, and is undone`() {
        val stuck = captureStore(SilentStarts(audio), music, inPlaceRuntime(clock, failures))

        stuck.send(CaptureCommand.Start(CaptureSource.MICROPHONE))
        clock.advanceBy(START_TIMEOUT - ONE_MILLI)
        assertEquals(CaptureState.Starting(CaptureSource.MICROPHONE, MusicChoice.KEEP_PLAYING), stuck.state.value)
        clock.advanceBy(ONE_MILLI)

        assertEquals(CaptureState.Failed(CaptureSource.MICROPHONE, CaptureProblem.DID_NOT_START), stuck.state.value)
        assertEquals(listOf("capture: capture from MICROPHONE did not start"), failures.map { "${it.feature}: ${it.message}" })
        assertEquals(listOf("stopCapture"), audio.calls)
    }

    @Test
    fun `when Playback cannot be reached the capture starts anyway and the failure is reported`() {
        music.unreachable = true

        store.send(CaptureCommand.Start(CaptureSource.MICROPHONE))

        assertEquals(CaptureState.Capturing(CaptureSource.MICROPHONE, quiet = false), store.state.value)
        assertEquals(listOf("capture: could not ask Playback about the song"), failures.map { "${it.feature}: ${it.message}" })
    }

    @Test
    fun `a start that throws counts as an unavailable source`() {
        val broken = captureStore(ThrowingStarts(audio), music, inPlaceRuntime(clock, failures))

        broken.send(CaptureCommand.Start(CaptureSource.LINE_IN))

        assertEquals(CaptureState.Failed(CaptureSource.LINE_IN, CaptureProblem.SOURCE_UNAVAILABLE), broken.state.value)
    }

    @Test
    fun `a stop that throws is reported and capture is off`() {
        val broken = captureStore(ThrowingStops(audio), music, inPlaceRuntime(clock, failures))
        broken.send(CaptureCommand.Start(CaptureSource.MICROPHONE))

        broken.send(CaptureCommand.Stop)

        assertEquals(CaptureState.Off, broken.state.value)
        assertEquals(listOf("capture: the capture did not stop"), failures.map { "${it.feature}: ${it.message}" })
    }

    @Test
    fun `pausing a song that throws is reported and capture goes on`() {
        music.playing = true
        music.pauseFails = true

        store.send(CaptureCommand.Start(CaptureSource.MICROPHONE))
        store.send(CaptureCommand.ChooseMusic(MusicChoice.PAUSE))

        assertEquals(CaptureState.Capturing(CaptureSource.MICROPHONE, quiet = false), store.state.value)
        assertEquals(listOf("capture: could not pause the song"), failures.map { "${it.feature}: ${it.message}" })
    }

    @Test
    fun `stopping ends the capture`() {
        store.send(CaptureCommand.Start(CaptureSource.MICROPHONE))

        store.send(CaptureCommand.Stop)

        assertEquals(CaptureState.Off, store.state.value)
        assertEquals(listOf("startCapture(MICROPHONE)", "stopCapture"), audio.calls)
    }

    @Test
    fun `the tile toggles the capture on and off`() {
        store.send(CaptureCommand.Toggle(CaptureSource.MICROPHONE))
        assertEquals(CaptureState.Capturing(CaptureSource.MICROPHONE, quiet = false), store.state.value)

        store.send(CaptureCommand.Toggle(CaptureSource.MICROPHONE))

        assertEquals(CaptureState.Off, store.state.value)
    }

    @Test
    fun `switching source while capturing restarts from the new one without asking again`() {
        music.playing = true
        store.send(CaptureCommand.Start(CaptureSource.MICROPHONE))
        store.send(CaptureCommand.ChooseMusic(MusicChoice.KEEP_PLAYING))

        store.send(CaptureCommand.Start(CaptureSource.LINE_IN))

        assertEquals(CaptureState.Capturing(CaptureSource.LINE_IN, quiet = false), store.state.value)
        assertEquals(listOf("startCapture(MICROPHONE)", "startCapture(LINE_IN)"), audio.calls)
    }

    /** Playback, as far as Capture sees it. */
    private class FakeMusic : MusicControl {
        var playing = false
        var unreachable = false
        var pauseFails = false
        var pauses = 0

        override suspend fun isPlaying(): Boolean {
            check(!unreachable) { "Playback is not there" }
            return playing
        }

        override suspend fun pause() {
            check(!pauseFails) { "Playback would not pause" }
            pauses++
            playing = false
        }
    }

    /** An adapter whose starts are accepted but never announced, as with a device that hangs. */
    private class SilentStarts(
        private val audio: FakeAudioAdapter,
    ) : AudioAdapter by audio {
        override suspend fun startCapture(source: AdapterSource): CaptureFailure? = null
    }

    private class ThrowingStarts(
        private val audio: FakeAudioAdapter,
    ) : AudioAdapter by audio {
        override suspend fun startCapture(source: AdapterSource): CaptureFailure? = error("the device would not open")
    }

    private class ThrowingStops(
        private val audio: FakeAudioAdapter,
    ) : AudioAdapter by audio {
        override suspend fun stopCapture(): Unit = error("the device would not close")
    }

    private companion object {
        val ONE_MILLI = Millis(1)
    }
}
