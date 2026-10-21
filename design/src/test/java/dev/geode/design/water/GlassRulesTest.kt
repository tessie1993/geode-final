package dev.geode.design.water

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassRulesTest {
    @Test
    fun `glass is frosted on Android 12 and later with a blur slot`() {
        assertEquals(GlassTier.Frosted, GlassTier.select(sdkInt = 31, reduceTransparency = false, blurGranted = true))
        assertEquals(GlassTier.Frosted, GlassTier.select(sdkInt = 36, reduceTransparency = false, blurGranted = true))
    }

    @Test
    fun `glass is tinted without RenderEffect, with less transparency, or without a slot`() {
        assertEquals(GlassTier.Tinted, GlassTier.select(sdkInt = 30, reduceTransparency = false, blurGranted = true))
        assertEquals(GlassTier.Tinted, GlassTier.select(sdkInt = 36, reduceTransparency = true, blurGranted = true))
        assertEquals(GlassTier.Tinted, GlassTier.select(sdkInt = 36, reduceTransparency = false, blurGranted = false))
    }

    @Test
    fun `glass under text never drops under 88 percent`() {
        assertEquals(0.88f, GlassOpacity.of(0f, carriesText = true, tier = GlassTier.Frosted, reduceTransparency = false), EPS)
        assertEquals(0.88f, GlassOpacity.of(-1f, carriesText = true, tier = GlassTier.Frosted, reduceTransparency = false), EPS)
    }

    @Test
    fun `glass under text rises with the backdrop's brightness`() {
        assertEquals(0.92f, GlassOpacity.of(0.5f, true, GlassTier.Frosted, false), EPS)
        assertEquals(0.96f, GlassOpacity.of(1f, true, GlassTier.Frosted, false), EPS)
        assertEquals(0.96f, GlassOpacity.of(2f, true, GlassTier.Frosted, false), EPS)
    }

    @Test
    fun `glass without text may be thinner`() {
        assertEquals(0.78f, GlassOpacity.of(0f, carriesText = false, tier = GlassTier.Frosted, reduceTransparency = false), EPS)
        assertEquals(0.84f, GlassOpacity.of(0.5f, false, GlassTier.Frosted, false), EPS)
        assertEquals(0.9f, GlassOpacity.of(1f, false, GlassTier.Frosted, false), EPS)
    }

    @Test
    fun `tinted glass is nearly solid stone`() {
        assertEquals(0.94f, GlassOpacity.of(0f, true, GlassTier.Tinted, false), EPS)
        assertEquals(0.94f, GlassOpacity.of(0f, false, GlassTier.Tinted, false), EPS)
        assertEquals(0.96f, GlassOpacity.of(1f, true, GlassTier.Tinted, false), EPS)
    }

    @Test
    fun `less transparency makes glass opaque`() {
        assertEquals(1f, GlassOpacity.of(0f, false, GlassTier.Frosted, reduceTransparency = true), EPS)
        assertEquals(1f, GlassOpacity.of(1f, true, GlassTier.Tinted, reduceTransparency = true), EPS)
    }

    @Test
    fun `the text floor is the token's`() {
        assertEquals(dev.geode.design.forest.ForestGlass.TEXT_OPACITY_MIN, GlassOpacity.TEXT_OPACITY_MIN, 0f)
    }

    @Test
    fun `two panels get a live blur and the third is refused`() {
        val a = Any()
        val b = Any()
        val c = Any()
        val budget = LiveBlurBudget().request(a).request(b).request(c)
        assertEquals(2, budget.live)
        assertTrue(budget.holds(a))
        assertTrue(budget.holds(b))
        assertFalse(budget.holds(c))
    }

    @Test
    fun `asking twice takes one slot`() {
        val a = Any()
        val once = LiveBlurBudget().request(a)
        assertSame(once, once.request(a))
        assertEquals(1, once.live)
    }

    @Test
    fun `a released slot can be taken by a waiting panel`() {
        val a = Any()
        val b = Any()
        val c = Any()
        val full = LiveBlurBudget().request(a).request(b)
        val freed = full.release(a)
        assertEquals(1, freed.live)
        assertFalse(freed.holds(a))
        assertTrue(freed.request(c).holds(c))
    }

    @Test
    fun `releasing a panel that holds nothing changes nothing`() {
        val budget = LiveBlurBudget().request(Any())
        assertSame(budget, budget.release(Any()))
    }

    @Test
    fun `a screen draws at most two live blurs`() {
        assertEquals(2, LiveBlurBudget.MAX_LIVE_BLURS)
        val many = (1..10).fold(LiveBlurBudget()) { budget, _ -> budget.request(Any()) }
        assertEquals(LiveBlurBudget.MAX_LIVE_BLURS, many.live)
    }

    private companion object {
        const val EPS = 1e-5f
    }
}
