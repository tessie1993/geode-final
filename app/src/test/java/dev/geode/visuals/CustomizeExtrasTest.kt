package dev.geode.visuals

import dev.geode.feature.customization.api.CustomizationState
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.core.BuiltInStyles
import dev.geode.feature.customization.core.Defaults
import dev.geode.feature.customization.ui.toCustomizeModel
import dev.geode.render.scene.StylePackRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Customize controls are exactly the Extras each Style's pack declares (#289): the packs are the one
 * source, so Customize shows no control the Style does not draw and misses none it does. Read from the
 * pack files themselves, the same files the engine draws with.
 */
class CustomizeExtrasTest {
    private val packs = StylePackRegistry.load { path -> File(PACK_ROOT, path).takeIf { it.isFile }?.readText() }

    @Test
    fun `the packs read`() {
        assertTrue(packs.problems.joinToString("\n"), packs.problems.isEmpty())
        assertFalse(packs.styles.isEmpty())
    }

    @Test
    fun `Customize offers exactly the Extras each Style's pack declares`() {
        packs.styles.forEach { pack ->
            val customization = Defaults.customization(BuiltInStyles.catalogue.manifestOf(pack.id))
            val model = CustomizationState(customization).toCustomizeModel()
            val declared = pack.extras.map { it.name }.toSet()
            val sliders = model.extras.map { (it.target as Target.Extra).kind.name.lowercase() }.toSet()
            assertEquals(pack.id.value, declared, sliders)
            val linkable =
                model.targets
                    .filterIsInstance<Target.Extra>()
                    .map { it.kind.name.lowercase() }
                    .toSet()
            assertEquals(pack.id.value, declared, linkable)
        }
    }

    @Test
    fun `each Extra keeps its pack's range and starts at its pack's default`() {
        packs.styles.forEach { pack ->
            val manifest = BuiltInStyles.catalogue.manifestOf(pack.id)
            assertEquals(pack.id.value, pack.extras.map { it.name }, manifest.extras.map { it.kind.name.lowercase() })
            pack.extras.zip(manifest.extras).forEach { (declared, spec) ->
                val where = "${pack.id.value} ${declared.name}"
                assertEquals(where, declared.min, spec.nativeMin)
                assertEquals(where, declared.max, spec.nativeMax)
                assertEquals(where, (declared.default - declared.min) / (declared.max - declared.min), spec.default, EXACT)
            }
        }
    }

    private companion object {
        /** Unit tests run in the module's folder; the packs live once, in :engine:scenes. */
        val PACK_ROOT = File("..", "engine/scenes/src/main/stylepacks")
        const val EXACT = 1e-6f
    }
}
