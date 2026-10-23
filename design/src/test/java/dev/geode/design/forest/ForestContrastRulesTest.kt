package dev.geode.design.forest

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The contrast test CI runs over every theme and mode (issue #70). */
class ForestContrastRulesTest {
    private val everyPalette =
        ForestMode.entries.map { mode -> Triple("Deep forest", mode, ForestPalettes.of(mode)) }

    @Test
    fun `every theme in every mode keeps all its text at WCAG AA, on glass over the worst backdrop too`() {
        for ((theme, mode, colors) in everyPalette) {
            val failures = ForestContrastRules.failures(colors, mode)
            assertTrue(
                "$theme $mode: " + failures.joinToString { "${it.name} is ${it.ratio}:1" },
                failures.isEmpty(),
            )
        }
    }

    @Test
    fun `glass that carries text is at least 88 percent opaque`() {
        assertEquals(0.88f, ForestGlass.TEXT_OPACITY_MIN, 0f)
        for ((_, _, colors) in everyPalette) {
            assertEquals(ForestGlass.TEXT_OPACITY_MIN, ForestGlass.textGlass(colors).alpha, 0.01f)
        }
    }

    @Test
    fun `thinner glass fails over a bright backdrop, which is why the floor is 88 percent`() {
        // The research measured secondary text at about 3.6:1 on 78% glass over white.
        val thin = 0.78f
        for ((theme, mode, colors) in everyPalette) {
            val failures = ForestContrastRules.failures(colors, mode, glassOpacity = thin)
            assertTrue("$theme $mode passed on thin glass", failures.isNotEmpty())
            assertTrue(failures.all { it.name.endsWith("glass over worst backdrop") })
        }
    }

    @Test
    fun `each text role is checked on the base, on glass and on glass over the worst backdrop`() {
        val pairs = ForestContrastRules.pairs(ForestPalettes.DeepForestDark, ForestMode.Dark)

        assertEquals(TEXT_ROLES * GROUNDS + 1, pairs.size)
        assertTrue(pairs.all { it.minimum == WcagContrast.AA_TEXT })
        val names = pairs.map { it.name }.toSet()
        for (role in listOf("primary text", "secondary text", "accent", "highlight")) {
            for (ground in listOf("base", "glass over base", "glass over worst backdrop")) {
                assertTrue("$role on $ground", "$role on $ground" in names)
            }
        }
        assertTrue("on-accent on accent" in names)
    }

    @Test
    fun `the worst backdrop is white behind dark glass and black behind light glass`() {
        assertEquals(Color.White, ForestGlass.worstBackdrop(ForestMode.Dark))
        assertEquals(Color.Black, ForestGlass.worstBackdrop(ForestMode.Light))
        val dark = ForestContrastRules.pairs(ForestPalettes.DeepForestDark, ForestMode.Dark)
        val worst = dark.first { it.name == "primary text on glass over worst backdrop" }.ground
        val expected =
            WcagContrast.over(ForestPalettes.DeepForestDark.glass, ForestGlass.TEXT_OPACITY_MIN, Color.White)
        assertEquals(expected, worst)
    }

    @Test
    fun `a palette with a weak colour is caught, and named`() {
        // Research note: rust #E08A5C drops to 2.75:1 over bright glass, so it is never text.
        val weakRust = ForestPalettes.DeepForestDark.copy(highlight = Color(0xFFE08A5C))

        val failures = ForestContrastRules.failures(weakRust, ForestMode.Dark).map { it.name }

        assertEquals(listOf("highlight on glass over worst backdrop"), failures)
    }

    @Test
    fun `on-accent text that disappears into the accent is caught`() {
        val colors = ForestPalettes.DeepForestDark
        val blind = colors.copy(onAccent = colors.accent)

        val failures = ForestContrastRules.failures(blind, ForestMode.Dark).map { it.name }

        assertEquals(listOf("on-accent on accent"), failures)
    }

    @Test
    fun `a pair passes exactly at its minimum`() {
        val pair = ContrastPair("black on white", Color.Black, Color.White, minimum = 21f)

        assertEquals(21f, pair.ratio, 0.001f)
        assertTrue(pair.copy(minimum = pair.ratio).passes)
        assertFalse(pair.copy(minimum = pair.ratio + 0.01f).passes)
    }

    private companion object {
        const val TEXT_ROLES = 4
        const val GROUNDS = 3
    }
}
