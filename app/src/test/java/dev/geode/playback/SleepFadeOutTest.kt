package dev.geode.playback

import dev.geode.engine.types.Millis
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepFadeOutTest {
    @Test
    fun `the fade starts its length before the timer runs out, or at once when less is left`() {
        assertEquals(Millis(57_000), SleepFadeOut.fadeStartsIn(Millis(60_000)))
        assertEquals(Millis.ZERO, SleepFadeOut.fadeStartsIn(Millis(3_000)))
        assertEquals(Millis.ZERO, SleepFadeOut.fadeStartsIn(Millis(1_000)))
    }
}
