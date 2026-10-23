package dev.geode.design.forest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ForestPalettesTest {
    @Test
    fun `each mode has its own palette`() {
        assertSame(ForestPalettes.DeepForestDark, ForestPalettes.of(ForestMode.Dark))
        assertSame(ForestPalettes.DeepForestLight, ForestPalettes.of(ForestMode.Light))
    }

    @Test
    fun `the forest theme starts dark`() {
        assertEquals(ForestMode.Dark, ForestMode.Default)
    }

    @Test
    fun `the dark palette sits on a dark base and the light one on a light base`() {
        assertTrue(WcagContrast.relativeLuminance(ForestPalettes.of(ForestMode.Dark).base) < DARK_BASE_MAX)
        assertTrue(WcagContrast.relativeLuminance(ForestPalettes.of(ForestMode.Light).base) > LIGHT_BASE_MIN)
    }

    private companion object {
        const val DARK_BASE_MAX = 0.05f
        const val LIGHT_BASE_MIN = 0.7f
    }
}
