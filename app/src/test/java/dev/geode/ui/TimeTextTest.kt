package dev.geode.ui

import dev.geode.engine.types.Millis
import org.junit.Assert.assertEquals
import org.junit.Test

class TimeTextTest {
    @Test
    fun `whole seconds are shown and the rest is dropped`() {
        assertEquals("0:00", Millis(0L).minutesSeconds())
        assertEquals("0:59", Millis(59_999L).minutesSeconds())
        assertEquals("1:01", Millis(61_000L).minutesSeconds())
        assertEquals("3:25", Millis(205_400L).minutesSeconds())
    }

    @Test
    fun `seconds are padded to two digits`() {
        assertEquals("10:05", Millis(605_000L).minutesSeconds())
    }

    @Test
    fun `an hour or more stays in minutes`() {
        assertEquals("60:00", Millis(3_600_000L).minutesSeconds())
        assertEquals("125:30", Millis(7_530_000L).minutesSeconds())
    }

    @Test
    fun `a negative time reads as zero`() {
        assertEquals("0:00", Millis(-5_000L).minutesSeconds())
    }
}
