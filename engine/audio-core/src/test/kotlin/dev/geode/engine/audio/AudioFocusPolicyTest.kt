package dev.geode.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Geode's own player follows ExoPlayer's audio focus rules: calls pause it, other players stop it, ducking lowers it. */
class AudioFocusPolicyTest {
    private val ducked = FocusState(ducked = true)
    private val suppressed = FocusState(suppressed = true)

    private data class Row(
        val from: FocusState,
        val change: FocusChange,
        val to: FocusState,
        val stop: Boolean,
    )

    private val table =
        listOf(
            Row(FocusState(), FocusChange.GAIN, FocusState(), stop = false),
            Row(suppressed, FocusChange.GAIN, FocusState(), stop = false),
            Row(ducked, FocusChange.GAIN, FocusState(), stop = false),
            Row(FocusState(), FocusChange.LOSS, FocusState(), stop = true),
            Row(ducked, FocusChange.LOSS, FocusState(), stop = true),
            Row(FocusState(), FocusChange.LOSS_TRANSIENT, suppressed, stop = false),
            Row(ducked, FocusChange.LOSS_TRANSIENT, suppressed, stop = false),
            Row(FocusState(), FocusChange.LOSS_TRANSIENT_CAN_DUCK, ducked, stop = false),
            Row(suppressed, FocusChange.LOSS_TRANSIENT_CAN_DUCK, FocusState(suppressed = true, ducked = true), stop = false),
        )

    @Test
    fun `each focus change moves the player as the table says`() {
        for (row in table) {
            val outcome = AudioFocusPolicy.onChange(row.from, row.change)
            assertEquals("$row", row.to, outcome.state)
            assertEquals("$row", row.stop, outcome.stopPlaying)
        }
    }

    @Test
    fun `the engine plays only when wanted and not held back by a call`() {
        assertTrue(AudioFocusPolicy.engineRuns(playWhenReady = true, FocusState()))
        assertTrue(AudioFocusPolicy.engineRuns(playWhenReady = true, ducked))
        assertFalse(AudioFocusPolicy.engineRuns(playWhenReady = true, suppressed))
        assertFalse(AudioFocusPolicy.engineRuns(playWhenReady = false, FocusState()))
    }

    @Test
    fun `ducking lowers the listener's own volume`() {
        assertEquals(0.5f, AudioFocusPolicy.engineVolume(0.5f, FocusState()), 0f)
        assertEquals(0.5f * AudioFocusPolicy.DUCK_VOLUME, AudioFocusPolicy.engineVolume(0.5f, ducked), 1e-6f)
    }

    @Test
    fun `android focus codes map to changes`() {
        assertEquals(FocusChange.GAIN, FocusChange.fromAndroid(1))
        assertEquals(FocusChange.LOSS, FocusChange.fromAndroid(-1))
        assertEquals(FocusChange.LOSS_TRANSIENT, FocusChange.fromAndroid(-2))
        assertEquals(FocusChange.LOSS_TRANSIENT_CAN_DUCK, FocusChange.fromAndroid(-3))
        assertNull(FocusChange.fromAndroid(0))
        assertNull(FocusChange.fromAndroid(2))
    }
}
