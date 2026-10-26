package dev.geode.visuals

import dev.geode.engine.types.SceneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the Style warm-up (#304) runs again, and in what order it draws. */
class StyleWarmUpTest {
    private val stamp = StyleWarmUp.stamp("1700000000000", "Arm/Mali-G710/OpenGL ES 3.2", listOf("b.milk", "a.milk"))

    @Test
    fun `a warm-up done for this install, driver and presets is not done again`() {
        assertFalse(StyleWarmUp.due(stamp, stamp))
        assertEquals(stamp, StyleWarmUp.stamp("1700000000000", "Arm/Mali-G710/OpenGL ES 3.2", listOf("a.milk", "b.milk")))
    }

    @Test
    fun `an update, a new driver or another set of presets warms again`() {
        assertTrue(StyleWarmUp.due(null, stamp))
        assertNotEquals(stamp, StyleWarmUp.stamp("1700000999000", "Arm/Mali-G710/OpenGL ES 3.2", listOf("a.milk", "b.milk")))
        assertNotEquals(stamp, StyleWarmUp.stamp("1700000000000", "Arm/Mali-G710/OpenGL ES 3.2 v2", listOf("a.milk", "b.milk")))
        assertNotEquals(stamp, StyleWarmUp.stamp("1700000000000", "Arm/Mali-G710/OpenGL ES 3.2", listOf("a.milk", "b.milk", "c.milk")))
        assertTrue(StyleWarmUp.due(stamp, StyleWarmUp.stamp("1700000000000", "x", emptyList())))
    }

    @Test
    fun `MilkDrop is warmed last, with every other Style in the engine's order before it`() {
        val milkdrop = SceneId("milkdrop")
        val styles = listOf(SceneId("aurora"), milkdrop, SceneId("tunnel"))

        assertEquals(listOf(SceneId("aurora"), SceneId("tunnel"), milkdrop), StyleWarmUp.order(styles, milkdrop))
        assertEquals(listOf(SceneId("aurora")), StyleWarmUp.order(listOf(SceneId("aurora")), milkdrop))
    }
}
