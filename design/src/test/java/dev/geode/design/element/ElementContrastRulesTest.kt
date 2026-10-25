package dev.geode.design.element

import androidx.compose.ui.graphics.Color
import dev.geode.design.forest.WcagContrast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The contrast test CI runs over every Element (#103): text on label surfaces keeps WCAG AA. */
class ElementContrastRulesTest {
    @Test
    fun `every Element keeps all its text at WCAG AA on every ground`() {
        for (element in ElementSkins.all) {
            val failures = ElementContrastRules.failures(element.colors)
            assertTrue(
                "${element.key}: " + failures.joinToString { "${it.name} is ${it.ratio}:1" },
                failures.isEmpty(),
            )
        }
    }

    @Test
    fun `each text role is checked on the background, the deep background and both label surfaces`() {
        val pairs = ElementContrastRules.pairs(ElementSkins.Water.colors)

        assertEquals(TEXT_ROLES * GROUNDS + 2, pairs.size)
        assertTrue(pairs.all { it.minimum == WcagContrast.AA_TEXT })
        val names = pairs.map { it.name }.toSet()
        for (role in listOf("primary text", "secondary text", "accent", "active", "error")) {
            for (ground in listOf("background", "deep background", "surface", "raised surface")) {
                assertTrue("$role on $ground", "$role on $ground" in names)
            }
        }
        assertTrue("on-accent on accent" in names)
        assertTrue("on-active on active" in names)
    }

    @Test
    fun `secondary text too dim for the raised surface is caught, and named`() {
        // #7E9693 keeps 4.5:1 on Water's darker grounds (4.9 on its surface) but only 4.2 on the raised one.
        val dim = ElementSkins.Water.colors.copy(textSecondary = Color(0xFF7E9693))

        val failures = ElementContrastRules.failures(dim).map { it.name }

        assertEquals(listOf("secondary text on raised surface"), failures)
    }

    @Test
    fun `text that disappears into a filled control is caught`() {
        val water = ElementSkins.Water.colors

        assertEquals(
            listOf("on-accent on accent"),
            ElementContrastRules.failures(water.copy(onAccent = water.accent)).map { it.name },
        )
        assertEquals(
            listOf("on-active on active"),
            ElementContrastRules.failures(water.copy(onActive = water.active)).map { it.name },
        )
    }

    @Test
    fun `the error colour is held to the same rule as text`() {
        val water = ElementSkins.Water.colors

        val failures = ElementContrastRules.failures(water.copy(error = water.surface)).map { it.name }

        assertEquals(GROUNDS, failures.size)
        assertTrue(failures.all { it.startsWith("error on") })
    }

    private companion object {
        const val TEXT_ROLES = 5
        const val GROUNDS = 4
    }
}
