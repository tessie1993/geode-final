package dev.geode.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartedCountTest {
    @Test
    fun `the first start brings the app forward and the last stop sends it back`() {
        val count = StartedCount(initiallyStarted = false)
        assertFalse(count.started)
        assertTrue(count.onStart())
        assertTrue(count.started)
        assertFalse(count.onStart())
        assertFalse(count.onStop())
        assertTrue(count.started)
        assertTrue(count.onStop())
        assertFalse(count.started)
    }

    @Test
    fun `registered while the app shows, the activity already started counts`() {
        val count = StartedCount(initiallyStarted = true)
        assertTrue(count.started)
        assertTrue(count.onStop())
        assertFalse(count.started)
    }

    @Test
    fun `a stop never counts below none`() {
        val count = StartedCount(initiallyStarted = false)
        assertFalse(count.onStop())
        assertFalse(count.started)
        assertTrue(count.onStart())
    }
}
