package dev.geode.render.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The packs that ship, read the way the app reads them. Holds for any set of packs, not a particular one. */
class ShippedStylePacksTest {
    private val registry = StylePacks.registry

    @Test
    fun `every pack reads`() {
        assertTrue(registry.problems.joinToString("\n"), registry.problems.isEmpty())
        assertFalse(registry.styles.isEmpty())
    }

    @Test
    fun `every renderer has its family declared`() {
        assertEquals(SceneKind.entries.toSet(), registry.families.map { it.kind }.toSet())
    }

    @Test
    fun `every shader Style ships its shader and no other Style names one`() {
        registry.styles.forEach { pack ->
            if (pack.kind == SceneKind.SHADER) {
                val path = checkNotNull(pack.shaderPath) { pack.id.value }
                assertNotNull(path, checkNotNull(javaClass.classLoader).getResource(path))
            } else {
                assertEquals(pack.id.value, null, pack.shaderPath)
            }
        }
    }

    @Test
    fun `every Style has a label and the picker lists every Style once`() {
        registry.styles.forEach { assertTrue(it.id.value, it.label.isNotBlank()) }
        assertEquals(registry.ids.size, registry.ids.toSet().size)
        assertEquals(registry.ids.toSet(), SceneKind.entries.flatMap { registry.idsOf(it) }.toSet())
    }

    @Test
    fun `a marched Style is a shader Style`() {
        registry.styles.filter { SceneCapabilities.isMarched(it.id) }.forEach { assertEquals(SceneKind.SHADER, it.kind) }
    }

    @Test
    fun `the label of a Style is its pack's and an unknown id is title-cased`() {
        registry.styles.forEach { assertEquals(it.label, SceneCapabilities.label(it.id)) }
        assertEquals("No Such Style", SceneCapabilities.label(dev.geode.engine.types.SceneId("no_such_style")))
    }
}
