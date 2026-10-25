package dev.geode.render.scene

import dev.geode.engine.types.SceneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saved presets and settings outlive the Styles they name. One that names a deleted Style loads
 * the default Style instead, with no error; one that names a Style that still exists is untouched.
 */
class StoredStyleFallbackTest {
    /** The ids of the deleted Silk family, as presets saved before #86 still carry them. */
    private val deletedSilkIds =
        listOf(
            "silk_web",
            "silk_bloom",
            "silk_weave",
            "silk_shell",
            "silk_spiral",
            "silk_fold",
            "silk_hyper",
            "silk_resonance",
            "silk_curl",
            "silk_pendulum",
        ).map(::SceneId)

    private val everyStyle: List<SceneId> = StylePacks.ids

    @Test
    fun `a stored id naming a deleted Silk Style loads the default Style`() {
        deletedSilkIds.forEach { id ->
            assertFalse("$id is still a Style", SceneCapabilities.isKnown(id))
            assertEquals(SceneIds.DEFAULT, SceneCapabilities.storedOrDefault(id))
        }
    }

    @Test
    fun `a stored id naming a Style that exists loads that Style`() {
        everyStyle.forEach { id ->
            assertTrue("$id is not known", SceneCapabilities.isKnown(id))
            assertEquals(id, SceneCapabilities.storedOrDefault(id))
        }
    }

    @Test
    fun `the default Style is itself a Style`() {
        assertTrue(SceneCapabilities.isKnown(SceneIds.DEFAULT))
        assertEquals(SceneIds.DEFAULT, SceneCapabilities.storedOrDefault(SceneIds.DEFAULT))
    }

    @Test
    fun `an unknown id is described as the default Style it loads as`() {
        val defaultKind = SceneCapabilities.kindOf(SceneIds.DEFAULT)

        deletedSilkIds.forEach { id -> assertEquals(defaultKind, SceneCapabilities.kindOf(id)) }
        assertEquals(defaultKind, SceneCapabilities.kindOf(SceneId("")))
    }

    @Test
    fun `each Style reports the kind its pack names`() {
        StylePacks.registry.styles.forEach { pack ->
            assertEquals(pack.id.value, pack.kind, SceneCapabilities.kindOf(pack.id))
        }
    }

    @Test
    fun `no Style picker lists a Silk Style`() {
        assertTrue(everyStyle.none { it.value.startsWith("silk_") })
    }
}
