package dev.geode.feature.studio.api

import dev.geode.engine.types.Millis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** What the outside sees of Recordings (#317). */
class RecordingApiTest {
    @Test
    fun `nothing records until asked, and a Recording runs at most 30 minutes`() {
        assertEquals(RecordingState.Idle, StudioState().recording)
        assertEquals(Millis(30L * 60L * 1000L), RecordingState.LIMIT)
    }

    @Test
    fun `a Recording starts at zero, with nothing to warn about`() {
        val fresh = RecordingState.Recording()
        assertEquals(Millis.ZERO, fresh.elapsed)
        assertFalse(fresh.lowStorage)
        assertFalse(fresh.stopping)
        assertFalse(fresh.atLimit)
        assertEquals("", RecordingCommand.Start().song)
    }
}
