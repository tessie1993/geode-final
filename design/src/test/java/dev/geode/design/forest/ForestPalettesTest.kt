package dev.geode.design.forest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ForestPalettesTest {
    @Test
    fun `each theme and mode has its own palette`() {
        assertSame(ForestPalettes.DeepForestDark, ForestPalettes.of(ForestThemeId.DeepForest, ForestMode.Dark))
        assertSame(ForestPalettes.DeepForestLight, ForestPalettes.of(ForestThemeId.DeepForest, ForestMode.Light))
        assertSame(ForestPalettes.NightForestDark, ForestPalettes.of(ForestThemeId.NightForest, ForestMode.Dark))
        assertSame(ForestPalettes.NightForestLight, ForestPalettes.of(ForestThemeId.NightForest, ForestMode.Light))
        assertSame(ForestPalettes.EarthLichenDark, ForestPalettes.of(ForestThemeId.EarthLichen, ForestMode.Dark))
        assertSame(ForestPalettes.EarthLichenLight, ForestPalettes.of(ForestThemeId.EarthLichen, ForestMode.Light))

        val all = ForestThemeId.entries.flatMap { t -> ForestMode.entries.map { m -> ForestPalettes.of(t, m) } }
        assertEquals(6, all.toSet().size)
    }

    @Test
    fun `a fresh install starts on deep forest, dark`() {
        assertEquals(ForestThemeId.DeepForest, ForestThemeId.Default)
        assertEquals(ForestMode.Dark, ForestMode.Default)
    }

    @Test
    fun `dark palettes sit on a dark base and light ones on a light base`() {
        for (theme in ForestThemeId.entries) {
            val dark = ForestPalettes.of(theme, ForestMode.Dark)
            val light = ForestPalettes.of(theme, ForestMode.Light)
            assertTrue("$theme dark base", WcagContrast.relativeLuminance(dark.base) < DARK_BASE_MAX)
            assertTrue("$theme light base", WcagContrast.relativeLuminance(light.base) > LIGHT_BASE_MIN)
        }
    }

    private companion object {
        const val DARK_BASE_MAX = 0.05f
        const val LIGHT_BASE_MIN = 0.7f
    }
}
