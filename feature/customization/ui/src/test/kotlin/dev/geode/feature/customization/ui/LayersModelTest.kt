package dev.geode.feature.customization.ui

import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.CustomizationState
import dev.geode.feature.customization.api.Layer
import dev.geode.feature.customization.api.LayerLoop
import dev.geode.feature.customization.api.LoopPreset
import dev.geode.feature.customization.api.LoopTarget
import dev.geode.feature.customization.api.SharedGroup
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Layers section is built from: the model's Layer 2 and the commands its controls send. */
class LayersModelTest {
    private val tunnel = SceneId("tunnel")
    private val kaleido = SceneId("kaleido")
    private val milkdrop = SceneId("milkdrop")
    private val looks =
        listOf(LookChip(tunnel, "Tunnel"), LookChip(kaleido, "Kaleido"), LookChip(milkdrop, "MilkDrop", canBeLayer = false))

    private val customization =
        Customization(
            style = tunnel,
            shared = mapOf(SharedProperty.LAYER_OPACITY to 0.7f),
            extras = emptyMap(),
            macros = emptyList(),
            reactions = emptyList(),
            modulators = emptyList(),
            signals = emptyMap(),
        )

    private fun model(layer: Layer) = CustomizationState(customization.copy(layer = layer)).toCustomizeModel(looks = looks)

    @Test
    fun `Layer 2's picker offers every look but the Style itself and MilkDrop`() {
        val off = model(Layer.OFF)
        assertEquals(listOf(kaleido), off.layerLooks.map { it.style })
        assertTrue(off.someLookCannotBeLayer)
        assertFalse(CustomizationState(customization).toCustomizeModel(looks = looks.take(2)).someLookCannotBeLayer)
    }

    @Test
    fun `the model carries Layer 2 and names its Style as the looks do`() {
        assertNull(model(Layer.OFF).layerName)
        val on = model(Layer(kaleido))
        assertEquals(Layer(kaleido), on.layer)
        assertEquals("Kaleido", on.layerName)
        assertEquals("nebula", model(Layer(SceneId("nebula"))).layerName)
    }

    @Test
    fun `Layer 2's sliders are its Shared core rows, in their own section`() {
        val rows = model(Layer(kaleido)).shared.getValue(SharedGroup.LAYERS)
        assertEquals(
            listOf(SharedProperty.LAYER_OPACITY, SharedProperty.LAYER_INFLUENCE, SharedProperty.LAYER_SWAP).map { Target.Shared(it) },
            rows.map { it.target },
        )
        assertEquals(0.7f, rows.first().value)
        assertEquals(AdvancedSection.LAYERS, sectionOf(Target.Shared(SharedProperty.LAYER_SWAP)))
        assertEquals(AdvancedSection.EFFECTS, sectionOf(Target.Shared(SharedProperty.INVERT)))
    }

    @Test
    fun `the loop switch starts the first ready-made loop and stops any loop`() {
        assertEquals(CustomizationCommand.SetLayerLoop(LoopPreset.BREATHE.loop), loopToggled(Layer(kaleido)))
        assertEquals(CustomizationCommand.SetLayerLoop(null), loopToggled(Layer(kaleido, loop = LayerLoop())))
    }

    @Test
    fun `a loop's targets toggle, but its last one stays`() {
        val loop = LayerLoop(targets = setOf(LoopTarget.OPACITY))
        assertEquals(
            CustomizationCommand.SetLayerLoop(loop.copy(targets = setOf(LoopTarget.OPACITY, LoopTarget.SWAP))),
            loop.toggled(LoopTarget.SWAP),
        )
        assertNull(loop.toggled(LoopTarget.OPACITY))
        val two = loop.copy(targets = setOf(LoopTarget.OPACITY, LoopTarget.INFLUENCE))
        assertEquals(CustomizationCommand.SetLayerLoop(loop.copy(targets = setOf(LoopTarget.INFLUENCE))), two.toggled(LoopTarget.OPACITY))
    }
}
