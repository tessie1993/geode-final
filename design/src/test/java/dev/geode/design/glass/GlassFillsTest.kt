package dev.geode.design.glass

import dev.chrisbanes.haze.HazeState
import org.junit.Assert.assertEquals
import org.junit.Test

/** What each tier draws over each backdrop: live blur only in Full, nothing translucent in Matte. */
class GlassFillsTest {
    private val live = GlassBackdrop.Live(GlassSource(HazeState()))

    private fun fill(
        tier: GlassTier,
        backdrop: GlassBackdrop,
        frosting: Boolean = false,
    ) = GlassFills.of(tier, backdrop, frosting)

    @Test
    fun `live content is blurred live only in the full tier`() {
        assertEquals(GlassFill.LIVE, fill(GlassTier.FULL, live))
        assertEquals(GlassFill.MATTE, fill(GlassTier.CALM, live))
        assertEquals(GlassFill.MATTE, fill(GlassTier.MATTE, live))
    }

    @Test
    fun `a baked backdrop keeps its thin tint until the matte tier`() {
        assertEquals(GlassFill.TINT, fill(GlassTier.FULL, GlassBackdrop.Baked))
        assertEquals(GlassFill.TINT, fill(GlassTier.CALM, GlassBackdrop.Baked))
        assertEquals(GlassFill.MATTE, fill(GlassTier.MATTE, GlassBackdrop.Baked))
    }

    @Test
    fun `glass over the live Visual is matte until the engine frosts it`() {
        assertEquals(GlassFill.MATTE, fill(GlassTier.FULL, GlassBackdrop.Visual))
        assertEquals(GlassFill.TINT, fill(GlassTier.FULL, GlassBackdrop.Visual, frosting = true))
        assertEquals(GlassFill.TINT, fill(GlassTier.CALM, GlassBackdrop.Visual, frosting = true))
        assertEquals(GlassFill.MATTE, fill(GlassTier.MATTE, GlassBackdrop.Visual, frosting = true))
    }

    @Test
    fun `unknown content is always matte`() {
        for (tier in GlassTier.entries) assertEquals(GlassFill.MATTE, fill(tier, GlassBackdrop.Unknown, frosting = true))
    }
}
