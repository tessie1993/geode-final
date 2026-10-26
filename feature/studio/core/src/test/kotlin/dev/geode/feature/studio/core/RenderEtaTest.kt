package dev.geode.feature.studio.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Render's time left, from how fast its progress moved. */
class RenderEtaTest {
    @Test
    fun `nothing is promised before a whole window has been watched`() {
        val eta = RenderEta()
        assertNull(eta.sample(0f, 0))
        assertNull(eta.sample(0.1f, 6_000))
        assertNull(eta.sample(0.19f, 11_999))
    }

    @Test
    fun `after a window the time left follows the measured speed`() {
        val eta = RenderEta()
        eta.sample(0f, 0)
        // 0.25 in 12 s: 0.75 left takes 36 s.
        assertEquals(36L, eta.sample(0.25f, 12_000))
        // 0.5 in 20 s: 0.5 left takes 20 s.
        assertEquals(20L, eta.sample(0.5f, 20_000))
    }

    @Test
    fun `too little progress tells nothing yet`() {
        val eta = RenderEta()
        eta.sample(0f, 0)
        assertNull(eta.sample(0.019f, 12_000))
        assertEquals(588L, eta.sample(0.02f, 12_000))
    }

    @Test
    fun `a finished Render has no time left`() {
        val eta = RenderEta()
        eta.sample(0.5f, 0)
        assertEquals(0L, eta.sample(1.5f, 12_000))
    }

    @Test
    fun `progress going back measures afresh`() {
        val eta = RenderEta()
        eta.sample(0.5f, 0)
        assertNull(eta.sample(0.25f, 12_000))
        assertNull(eta.sample(0.5f, 23_999))
        // 0.25 in 12 s from the restart: 0.5 left takes 24 s.
        assertEquals(24L, eta.sample(0.5f, 24_000))
    }

    @Test
    fun `an old window slides, keeping the speed measured`() {
        val eta = RenderEta(windowSeconds = 10f, minProgress = 0.01f)
        eta.sample(0f, 0)
        // 0.25 in 25 s: past two windows, it keeps 0.1 per 10 s.
        assertEquals(75L, eta.sample(0.25f, 25_000))
        // Then 0.25 more in 5 s, measured over the 15 s since the slid start: 0.35 in 15 s.
        assertEquals(21L, eta.sample(0.5f, 30_000))
    }

    @Test
    fun `progress is kept within 0 to 1`() {
        val eta = RenderEta()
        eta.sample(-1f, 0)
        assertEquals(36L, eta.sample(0.25f, 12_000))
    }
}
