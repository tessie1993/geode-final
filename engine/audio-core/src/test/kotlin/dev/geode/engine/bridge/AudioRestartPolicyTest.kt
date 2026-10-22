package dev.geode.engine.bridge

import dev.geode.engine.types.Millis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRestartPolicyTest {
    private var clock = Millis(1_000L)
    private val policy = AudioRestartPolicy(now = { clock }, maxRestarts = 2, window = Millis(10_000L))
    private val playerCrash = EngineFailure.EngineCrashed("playerPlay")

    @Test
    fun `an audio engine that threw restarts`() {
        assertTrue(policy.shouldRestart(playerCrash))
        assertTrue(policy.shouldRestart(EngineFailure.OutOfMemory("dspProcess")))
    }

    @Test
    fun `a failure elsewhere does not restart the audio`() {
        assertFalse(policy.shouldRestart(EngineFailure.EngineCrashed("vizRender")))
        assertFalse(policy.shouldRestart(EngineFailure.EngineCrashed("analysisPull")))
    }

    @Test
    fun `a refused audio call is not a crash`() {
        assertFalse(policy.shouldRestart(EngineFailure.InvalidCall("playerPlay", NativeStatus.NULL_HANDLE)))
    }

    @Test
    fun `restarts stop once the budget for the window is spent`() {
        assertTrue(policy.shouldRestart(playerCrash))
        clock = Millis(5_000L)
        assertTrue(policy.shouldRestart(playerCrash))
        assertFalse(policy.shouldRestart(playerCrash))
    }

    @Test
    fun `a restart leaves the budget once the window has passed`() {
        assertTrue(policy.shouldRestart(playerCrash))
        clock = Millis(5_000L)
        assertTrue(policy.shouldRestart(playerCrash))
        clock = Millis(10_999L)
        assertFalse(policy.shouldRestart(playerCrash))
        clock = Millis(11_000L)
        assertTrue(policy.shouldRestart(playerCrash))
        assertFalse(policy.shouldRestart(playerCrash))
    }

    @Test
    fun `declined failures do not use up the budget`() {
        repeat(5) { policy.shouldRestart(EngineFailure.EngineCrashed("vizRender")) }
        assertTrue(policy.shouldRestart(playerCrash))
    }

    @Test
    fun `the default budget is three restarts a minute`() {
        val defaults = AudioRestartPolicy(now = { clock })
        repeat(AudioRestartPolicy.MAX_RESTARTS) { assertTrue(defaults.shouldRestart(playerCrash)) }
        assertFalse(defaults.shouldRestart(playerCrash))
        clock += AudioRestartPolicy.WINDOW
        assertTrue(defaults.shouldRestart(playerCrash))
        assertEquals(3, AudioRestartPolicy.MAX_RESTARTS)
        assertEquals(Millis(60_000L), AudioRestartPolicy.WINDOW)
    }
}
