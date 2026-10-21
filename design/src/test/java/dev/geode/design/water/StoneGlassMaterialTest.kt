package dev.geode.design.water

import androidx.compose.ui.graphics.Color
import dev.geode.design.forest.ForestMode
import dev.geode.design.forest.ForestPalettes
import dev.geode.design.forest.ForestThemeId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoneGlassMaterialTest {
    private fun material(
        theme: ForestThemeId,
        mode: ForestMode,
    ) = StoneGlassMaterial.of(theme, mode, ForestPalettes.of(theme, mode))

    @Test
    fun `each theme's glass is cut from its own stone`() {
        assertEquals(StoneGrain.Slate, material(ForestThemeId.DeepForest, ForestMode.Dark).grain)
        assertEquals(StoneGrain.Obsidian, material(ForestThemeId.NightForest, ForestMode.Light).grain)
        assertEquals(StoneGrain.Basalt, material(ForestThemeId.EarthLichen, ForestMode.Dark).grain)
    }

    @Test
    fun `the grain stays faint, between 3 and 6 percent`() {
        for (theme in ForestThemeId.entries) {
            for (mode in ForestMode.entries) {
                val alpha = material(theme, mode).grainAlpha
                assertTrue("$theme $mode grain $alpha", alpha in 0.03f..0.06f)
            }
        }
        assertEquals(WaterTokens.GRAIN_ALPHA_DARK, material(ForestThemeId.DeepForest, ForestMode.Dark).grainAlpha)
        assertEquals(WaterTokens.GRAIN_ALPHA_LIGHT, material(ForestThemeId.DeepForest, ForestMode.Light).grainAlpha)
    }

    @Test
    fun `the tint is the theme's glass colour`() {
        for (theme in ForestThemeId.entries) {
            for (mode in ForestMode.entries) {
                assertEquals(ForestPalettes.of(theme, mode).glass, material(theme, mode).tint)
            }
        }
    }

    @Test
    fun `dark glass catches the theme's own light`() {
        val forest = ForestPalettes.of(ForestThemeId.DeepForest, ForestMode.Dark)
        val night = ForestPalettes.of(ForestThemeId.NightForest, ForestMode.Dark)
        val earth = ForestPalettes.of(ForestThemeId.EarthLichen, ForestMode.Dark)
        assertEquals(forest.textPrimary, material(ForestThemeId.DeepForest, ForestMode.Dark).edgeLight)
        assertEquals(night.accent, material(ForestThemeId.NightForest, ForestMode.Dark).edgeLight)
        assertEquals(earth.highlight, material(ForestThemeId.EarthLichen, ForestMode.Dark).edgeLight)
    }

    @Test
    fun `light glass catches sunlight`() {
        for (theme in ForestThemeId.entries) {
            assertEquals(Color.White, material(theme, ForestMode.Light).edgeLight)
        }
    }

    @Test
    fun `the shadow is tinted from the theme, never pure black`() {
        for (theme in ForestThemeId.entries) {
            val dark = ForestPalettes.of(theme, ForestMode.Dark)
            val light = ForestPalettes.of(theme, ForestMode.Light)
            assertEquals(dark.base, material(theme, ForestMode.Dark).shadow)
            assertEquals(light.textPrimary, material(theme, ForestMode.Light).shadow)
            assertNotEquals(Color.Black, material(theme, ForestMode.Dark).shadow)
        }
    }
}
