package dev.geode.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ExoPlayer's tap runs ahead of the speaker; the feed is told by how much, without a call per position query. */
class PresentationLeadTest {
    @Test
    fun `the lead is what the tap has seen beyond what is heard`() {
        assertEquals(500_000L, PresentationLead.of(tappedEndUs = 1_500_000L, heardUs = 1_000_000L))
    }

    @Test
    fun `a tap behind the speaker leads by nothing`() {
        assertEquals(0L, PresentationLead.of(tappedEndUs = 900_000L, heardUs = 1_000_000L))
    }

    @Test
    fun `a gap beyond any buffer is held to the most a tap is believed`() {
        assertEquals(PresentationLead.MAX_LEAD_US, PresentationLead.of(tappedEndUs = 9_000_000L, heardUs = 0L))
        assertEquals(PresentationLead.MAX_LEAD_US, PresentationLead.of(tappedEndUs = PresentationLead.MAX_LEAD_US, heardUs = 0L))
    }

    @Test
    fun `the first measurement is always sent`() {
        val lead = PresentationLead()
        assertEquals(480_000L, lead.offer(tappedEndUs = 480_000L, heardUs = 0L, nowMs = 0L))
    }

    @Test
    fun `a lead that barely moved is not sent again until the refresh`() {
        val lead = PresentationLead(stepUs = 2_000L, refreshMs = 250L)
        lead.offer(500_000L, 0L, nowMs = 1_000L)
        assertNull(lead.offer(501_999L, 0L, nowMs = 1_100L))
        assertNull(lead.offer(498_001L, 0L, nowMs = 1_249L))
        assertEquals(500_500L, lead.offer(500_500L, 0L, nowMs = 1_250L))
    }

    @Test
    fun `a lead that moved by a step is sent at once`() {
        val lead = PresentationLead(stepUs = 2_000L, refreshMs = 250L)
        lead.offer(500_000L, 0L, nowMs = 1_000L)
        assertEquals(502_000L, lead.offer(502_000L, 0L, nowMs = 1_001L))
        assertEquals(500_000L, lead.offer(500_000L, 0L, nowMs = 1_002L))
    }

    @Test
    fun `after a reset the next measurement goes out whatever it is`() {
        val lead = PresentationLead(stepUs = 2_000L, refreshMs = 250L)
        lead.offer(500_000L, 0L, nowMs = 1_000L)
        lead.reset()
        assertEquals(500_000L, lead.offer(500_000L, 0L, nowMs = 1_001L))
        assertNull(lead.offer(500_000L, 0L, nowMs = 1_002L))
    }
}
