package dev.geode.design.glass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The material's numbers are the #270 research's, and the grain is the same on every run. */
class GlassTokensTest {
    @Test
    fun `each kind of glass has the researched blur and tint`() {
        assertEquals(listOf(24f, 32f, 40f), GlassKind.entries.map { it.blur.value })
        assertEquals(listOf(0.60f, 0.60f, 0.65f), GlassKind.entries.map { it.tintAlpha })
        assertTrue(GlassKind.entries.all { it.matteAlpha >= 0.94f })
    }

    @Test
    fun `the material is clamped, a little quieter, grained and edged as researched`() {
        assertEquals(0.35f, GlassTokens.BACKDROP_CLAMP, 0f)
        assertEquals(0.9f, GlassTokens.SATURATION, 0f)
        assertEquals(0.04f, GlassTokens.GRAIN_ALPHA, 0f)
        assertEquals(1f, GlassTokens.Edge.value, 0f)
        assertEquals(2, GlassTokens.LIVE_BLURS_MAX)
    }

    @Test
    fun `the grain tile is square, in range and the same every time`() {
        val tile = GlassGrain.tile(16)
        assertEquals(256, tile.size)
        assertTrue(tile.all { it in 0f..1f })
        assertTrue(tile.contentEquals(GlassGrain.tile(16)))
        assertTrue(tile.toSet().size > 200)
        assertEquals(0.5f, tile.average().toFloat(), 0.08f)
    }

    @Test
    fun `the grain depends on where it is and on its seed`() {
        assertEquals(GlassGrain.noise(3, 5), GlassGrain.noise(3, 5), 0f)
        assertTrue(GlassGrain.noise(3, 5) != GlassGrain.noise(5, 3))
        assertTrue(GlassGrain.tile(8, seed = 1).toList() != GlassGrain.tile(8, seed = 2).toList())
        assertEquals(GlassGrain.noise(1, 2), GlassGrain.tile(4)[2 * 4 + 1], 0f)
    }
}
