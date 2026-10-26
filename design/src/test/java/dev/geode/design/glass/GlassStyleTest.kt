package dev.geode.design.glass

import androidx.compose.ui.graphics.Color
import dev.geode.design.forest.WcagContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The person's adjustments never cost legibility. */
class GlassStyleTest {
    @Test
    fun `background dim only ever hides more of the backdrop`() {
        assertEquals(GlassTokens.BACKDROP_CLAMP, GlassStyle.clampFor(0f), 0f)
        assertEquals(GlassTokens.BACKDROP_CLAMP * 0.4f, GlassStyle.clampFor(0.6f), 0.0001f)
        assertEquals(0f, GlassStyle.clampFor(2f), 0f)
        assertEquals(GlassTokens.BACKDROP_CLAMP, GlassStyle.clampFor(-1f), 0f)
    }

    @Test
    fun `the bar is never thinner than its token`() {
        assertEquals(GlassKind.BAR.tintAlpha, GlassStyle.barTintFor(0.2f), 0f)
        assertEquals(0.72f, GlassStyle.barTintFor(0.72f), 0f)
        assertEquals(1f, GlassStyle.barTintFor(3f), 0f)
    }

    @Test
    fun `a style picks its tint per kind, the bar's from the person`() {
        val style = GlassStyle.of(dark = true, tier = GlassTier.FULL, barOpacity = 0.8f)
        assertEquals(0.8f, style.tintAlpha(GlassKind.BAR), 0f)
        assertEquals(GlassKind.CARD.tintAlpha, style.tintAlpha(GlassKind.CARD), 0f)
        assertEquals(GlassKind.SHEET.tintAlpha, style.tintAlpha(GlassKind.SHEET), 0f)
    }

    @Test
    fun `the backdrop fields recede with background dim`() {
        assertEquals(1f, GlassStyle.of(dark = true, tier = GlassTier.FULL).fieldStrength, 0.0001f)
        assertEquals(0.5f, GlassStyle.of(dark = true, tier = GlassTier.FULL, dim = 0.5f).fieldStrength, 0.0001f)
    }

    @Test
    fun `untouched settings give the palette itself`() {
        for (dark in listOf(true, false)) {
            val style = GlassStyle.of(dark, GlassTier.CALM)
            assertEquals(GlassPalettes.of(dark), style.colors)
            assertEquals(dark, style.dark)
            assertSame(GlassTier.CALM, style.tier)
        }
    }

    @Test
    fun `any accent intensity keeps the accent and its text readable`() {
        for (dark in listOf(true, false)) {
            for (intensity in listOf(0.5f, 1f, 1.5f)) {
                val style = GlassStyle.of(dark, GlassTier.FULL, accentIntensity = intensity)
                val failures = GlassContrastRules.failures(style.colors, dark)
                assertTrue("$dark $intensity: " + failures.joinToString { it.name }, failures.isEmpty())
            }
        }
    }

    @Test
    fun `a chosen text colour is kept where it reads and dropped where it would not`() {
        val cream = Color(0xFFFFF4DC)
        assertEquals(cream, GlassStyle.of(dark = true, tier = GlassTier.FULL, fontColor = cream).colors.textPrimary)
        val light = GlassStyle.of(dark = false, tier = GlassTier.FULL, fontColor = cream).colors
        assertEquals(GlassPalettes.Light.textPrimary, light.textPrimary)
        assertTrue(WcagContrast.ratio(light.textPrimary, light.base) >= WcagContrast.AA_TEXT)
    }
}
