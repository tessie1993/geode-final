package dev.geode.studio

import dev.geode.feature.customization.api.Automations
import dev.geode.feature.customization.api.StyleMark
import dev.geode.feature.customization.api.StylePool
import dev.geode.feature.customization.api.StyleSwitching
import dev.geode.feature.customization.core.BuiltInStyles
import dev.geode.feature.customization.core.Defaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A Render's Style switching, replayed by Customization's seeded rules (#343). */
class RenderStyleSwitcherTest {
    private val catalogue = BuiltInStyles.catalogue
    private val first = BuiltInStyles.all[0].id
    private val second = BuiltInStyles.all[1].id
    private val base = Defaults.customization(catalogue.manifestOf(first))

    private fun switching(switching: StyleSwitching) = base.copy(automations = Automations(style = switching))

    private fun styles(
        switcher: RenderStyleSwitcher,
        count: Int,
    ) = List(count) { switcher.next()?.style }

    @Test
    fun `the same frozen look switches to the same Styles every time`() {
        val look = switching(StyleSwitching(pool = StylePool.PICKS, seed = 7))
        val one = styles(RenderStyleSwitcher(look, SwitchingContext(), catalogue), 12)
        val two = styles(RenderStyleSwitcher(look, SwitchingContext(), catalogue), 12)
        assertEquals(one, two)
        assertTrue(one.all { it != null })
        // Every switch changes the Style, and another seed picks another order.
        one.zipWithNext().forEach { (a, b) -> assertNotEquals(a, b) }
        val other = switching(StyleSwitching(pool = StylePool.PICKS, seed = 8))
        val reseeded = styles(RenderStyleSwitcher(other, SwitchingContext(), catalogue), 12)
        assertNotEquals(one, reseeded)
    }

    @Test
    fun `a pair goes back and forth, and the switched look carries its Automations`() {
        val look = switching(StyleSwitching(pool = StylePool.PAIR, chosen = listOf(first, second)))
        val switcher = RenderStyleSwitcher(look, SwitchingContext(), catalogue)
        assertEquals(listOf(second, first, second), styles(switcher, 3))
        assertEquals(look.automations, switcher.current.automations)
        assertEquals(second, switcher.current.style)
    }

    @Test
    fun `the frozen context decides the pool, and no switching or nowhere to go stays put`() {
        val visualizer = switching(StyleSwitching(pool = StylePool.VISUALIZER))
        assertNull(RenderStyleSwitcher(visualizer, SwitchingContext(), catalogue).next())
        assertEquals(second, RenderStyleSwitcher(visualizer, SwitchingContext(visualizer = listOf(first, second)), catalogue).next()?.style)
        val never = SwitchingContext(visualizer = listOf(first, second), marks = mapOf(second to StyleMark.NEVER))
        assertNull(RenderStyleSwitcher(visualizer, never, catalogue).next())
        val still = RenderStyleSwitcher(base, SwitchingContext(), catalogue)
        assertNull(still.next())
        assertEquals(base, still.current)
    }
}
