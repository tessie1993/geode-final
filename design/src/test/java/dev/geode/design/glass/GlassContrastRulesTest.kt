package dev.geode.design.glass

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Text over glass meets WCAG 1.4.3 over the worst backdrop in every theme mode (#324). */
class GlassContrastRulesTest {
    private val modes = listOf(true to GlassPalettes.Dark, false to GlassPalettes.Light)

    private fun name(dark: Boolean) = if (dark) "dark" else "light"

    @Test
    fun `both palettes keep every text role at WCAG AA on every ground`() {
        for ((dark, colors) in modes) {
            val failures = GlassContrastRules.failures(colors, dark)
            assertTrue(name(dark) + ": " + failures.joinToString { "${it.name} is ${it.ratio}:1" }, failures.isEmpty())
        }
    }

    @Test
    fun `the rules check every kind of glass, the matte fallback and every backdrop field`() {
        for ((dark, colors) in modes) {
            val names = GlassContrastRules.pairs(colors, dark).map { it.name }
            for (kind in GlassKind.entries) {
                val kindName = kind.name.lowercase()
                assertTrue(names.contains("secondary text on $kindName glass"))
                assertTrue(names.contains("secondary text on $kindName matte"))
            }
            colors.fields.indices.forEach { assertTrue(names.contains("error on backdrop field $it")) }
            assertTrue(names.contains("on-accent on accent"))
            assertTrue(names.contains("large primary text on the worst clamped backdrop"))
        }
    }

    @Test
    fun `without the clamp the thin tint fails over the worst backdrop, which is why it is there`() {
        for ((dark, colors) in modes) {
            val failures = GlassContrastRules.failures(colors, dark, clamp = 1f)
            assertTrue("${name(dark)} passed without a clamp", failures.isNotEmpty())
            assertTrue(failures.none { it.name.endsWith(" matte") || it.name.endsWith(" on base") })
        }
    }

    @Test
    fun `a palette whose text is its own glass is caught`() {
        val bad = GlassPalettes.Dark.copy(textSecondary = GlassPalettes.Dark.glass)
        assertTrue(GlassContrastRules.failures(bad, dark = true).any { it.name.startsWith("secondary text") })
    }

    @Test
    fun `the clamp shows only its share of the backdrop`() {
        val clamped = GlassContrast.clamped(Color.White, Color.Black, 0.35f)
        assertEquals(0.35f, clamped.red, 0.001f)
        assertEquals(Color.White, GlassContrast.clamped(Color.White, Color.Black, 1f))
        assertEquals(Color.Black, GlassContrast.clamped(Color.White, Color.Black, 0f))
    }

    @Test
    fun `the worst backdrop is white in the dark mode and black in the light one`() {
        assertEquals(Color.White, GlassContrast.worstBackdrop(dark = true))
        assertEquals(Color.Black, GlassContrast.worstBackdrop(dark = false))
    }

    @Test
    fun `glass and matte grounds mix the tint over the right backdrop`() {
        val colors = GlassPalettes.Dark
        val matte = GlassContrast.matteGround(colors, 1f, dark = true)
        assertEquals(colors.glass.red, matte.red, 0.001f)
        val clear = GlassContrast.glassGround(colors, 0f, 0.35f, dark = true)
        assertEquals(GlassContrast.clamped(Color.White, colors.base, 0.35f).red, clear.red, 0.001f)
        val field = GlassField(Color.White, alpha = 0f, x = 0f, y = 0f, reach = 1f)
        assertEquals(colors.base.green, GlassContrast.fieldGround(colors.base, field).green, 0.001f)
    }

    @Test
    fun `a readable colour is left alone`() {
        val text = GlassPalettes.Dark.textPrimary
        assertEquals(text, GlassContrast.readable(text, listOf(GlassPalettes.Dark.base), WcagContrast.AA_TEXT, Color.White))
    }

    @Test
    fun `an unreadable colour is moved until it reads`() {
        val ground = Color(0xFF202020)
        val moved = GlassContrast.readable(Color(0xFF303030), listOf(ground), WcagContrast.AA_TEXT, Color.White)
        assertTrue(WcagContrast.ratio(moved, ground) >= WcagContrast.AA_TEXT)
        assertTrue(moved.red > 0.19f)
    }

    @Test
    fun `a colour that can never read stops at its end`() {
        val moved = GlassContrast.readable(Color.Gray, listOf(Color.White), 30f, Color.White)
        assertEquals(Color.White.red, moved.red, 0.001f)
    }
}
