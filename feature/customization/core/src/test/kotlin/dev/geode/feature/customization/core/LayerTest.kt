package dev.geode.feature.customization.core

import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.CustomizationFact
import dev.geode.feature.customization.api.CustomizationState
import dev.geode.feature.customization.api.Layer
import dev.geode.feature.customization.api.LayerBlend
import dev.geode.feature.customization.api.LayerInfluence
import dev.geode.feature.customization.api.LayerLoop
import dev.geode.feature.customization.api.LoopPreset
import dev.geode.feature.customization.api.LoopRate
import dev.geode.feature.customization.api.LoopShape
import dev.geode.feature.customization.api.LoopTarget
import dev.geode.feature.customization.api.PresetPart
import dev.geode.feature.customization.api.SharedGroup
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.core.Fixtures.JULIA
import dev.geode.feature.customization.core.Fixtures.KALEIDO
import dev.geode.feature.customization.core.Fixtures.MILKDROP
import dev.geode.feature.customization.core.Fixtures.TUNNEL
import dev.geode.feature.customization.core.Fixtures.julia
import dev.geode.feature.customization.core.Fixtures.styles
import dev.geode.kernel.Emit
import dev.geode.kernel.Next
import dev.geode.kernel.Perform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Layer 2's Commands as a transition table (ADR-0003), each from a state with no history, with undo
 * history and with redo history, and what Layer 2 does across a change of Style, a reset and a preset.
 */
class LayerTest {
    private val reducer = CustomizationReducer(styles)

    private val zoomed: Customization = Edits.setShared(julia, SharedProperty.ZOOM, 2f)
    private val layered: Customization = julia.copy(layer = Layer(TUNNEL))
    private val looping: Customization = layered.copy(layer = layered.layer.copy(loop = LoopPreset.BREATHE.loop))

    private val fresh = CustomizationState(julia)
    private val withUndo = CustomizationState(zoomed, undo = listOf(julia))
    private val withRedo = CustomizationState(julia, redo = listOf(zoomed))
    private val on = CustomizationState(layered, undo = listOf(julia))

    private fun command(command: CustomizationCommand) = CustomizationEvent.Command(command)

    private fun publish(customization: Customization) = Perform(CustomizationWork.Publish(customization))

    private fun edited(
        state: CustomizationState,
        next: Customization,
    ): Next<CustomizationState, CustomizationEffect> = Next(CustomizationState(next, state.undo + state.current), listOf(publish(next)))

    private data class Row(
        val name: String,
        val state: CustomizationState,
        val event: CustomizationEvent,
        val expected: Next<CustomizationState, CustomizationEffect>,
    )

    private fun edits(
        name: String,
        command: CustomizationCommand,
        next: (Customization) -> Customization,
    ): List<Row> =
        listOf(fresh, withUndo, withRedo).map { state ->
            Row("$name from ${state.undo.size}/${state.redo.size}", state, command(command), edited(state, next(state.current)))
        }

    private fun withLayer(
        customization: Customization,
        layer: Layer,
    ) = customization.copy(layer = layer)

    private val rows: List<Row> =
        edits("lay a Style over", CustomizationCommand.ChooseLayerStyle(TUNNEL)) { withLayer(it, Layer(TUNNEL)) } +
            edits("set the blend", CustomizationCommand.SetLayerBlend(LayerBlend.DIFFERENCE)) {
                withLayer(it, Layer(blend = LayerBlend.DIFFERENCE))
            } +
            edits("set the influence", CustomizationCommand.SetLayerInfluence(LayerInfluence.SWIRL)) {
                withLayer(it, Layer(influence = LayerInfluence.SWIRL))
            } +
            edits("set a loop", CustomizationCommand.SetLayerLoop(LoopPreset.PULSE.loop)) {
                withLayer(it, Layer(loop = LoopPreset.PULSE.loop))
            } +
            edits("set a loop past its depth", CustomizationCommand.SetLayerLoop(LayerLoop(depth = 3f))) {
                withLayer(it, Layer(loop = LayerLoop(depth = 1f)))
            } +
            listOf(
                Row("turn Layer 2 off", on, command(CustomizationCommand.ChooseLayerStyle(null)), edited(on, julia)),
                Row("turn off a Layer 2 that is off", fresh, command(CustomizationCommand.ChooseLayerStyle(null)), Next(fresh)),
                Row("MilkDrop cannot be Layer 2", fresh, command(CustomizationCommand.ChooseLayerStyle(MILKDROP)), Next(fresh)),
                Row("a Style cannot be its own Layer 2", fresh, command(CustomizationCommand.ChooseLayerStyle(JULIA)), Next(fresh)),
                Row(
                    "an unknown Style cannot be Layer 2",
                    fresh,
                    command(CustomizationCommand.ChooseLayerStyle(SceneId("nope"))),
                    Next(fresh),
                ),
                Row("the same Layer 2 again", on, command(CustomizationCommand.ChooseLayerStyle(TUNNEL)), Next(on)),
                Row(
                    "another Style as Layer 2",
                    on,
                    command(CustomizationCommand.ChooseLayerStyle(KALEIDO)),
                    edited(on, julia.copy(layer = Layer(KALEIDO))),
                ),
                Row("the same blend", on, command(CustomizationCommand.SetLayerBlend(LayerBlend.SCREEN)), Next(on)),
                Row(
                    "a loop that moves nothing is no loop",
                    fresh,
                    command(CustomizationCommand.SetLayerLoop(LayerLoop(targets = emptySet()))),
                    Next(fresh),
                ),
                Row(
                    "stop the loop",
                    CustomizationState(looping),
                    command(CustomizationCommand.SetLayerLoop(null)),
                    edited(CustomizationState(looping), layered),
                ),
                Row(
                    "choosing Layer 2's Style as the Style turns Layer 2 off",
                    on,
                    command(CustomizationCommand.ChooseStyle(TUNNEL)),
                    Edits.switchStyle(layered, styles.manifestOf(JULIA), styles.manifestOf(TUNNEL)).let { next ->
                        Next(
                            CustomizationState(next, listOf(julia, layered), stylesBack = listOf(layered)),
                            listOf(publish(next), Emit(CustomizationFact.StyleChanged(TUNNEL))),
                        )
                    },
                ),
                Row("reset to Style turns Layer 2 off", on, command(CustomizationCommand.ResetToStyle), edited(on, julia)),
            )

    @Test
    fun `every Layer 2 Command does what its row says`() {
        rows.forEach { row -> assertEquals(row.name, row.expected, reducer.reduce(row.state, row.event)) }
    }

    @Test
    fun `Layer 2 carries over to another Style, its Style turned off only when it becomes the Style`() {
        val tunnelOver = Edits.switchStyle(looping, styles.manifestOf(JULIA), styles.manifestOf(KALEIDO))
        assertEquals(looping.layer, tunnelOver.layer)
        val itself = Edits.switchStyle(looping, styles.manifestOf(JULIA), styles.manifestOf(TUNNEL))
        assertEquals(looping.layer.copy(style = null), itself.layer)
        assertFalse(itself.layer.on)
    }

    @Test
    fun `Layer 2's opacity, influence and swap are Shared core properties in their own group and preset part`() {
        val layers = SharedProperty.entries.filter { it.group == SharedGroup.LAYERS }
        assertEquals(listOf(SharedProperty.LAYER_OPACITY, SharedProperty.LAYER_INFLUENCE, SharedProperty.LAYER_SWAP), layers)
        assertTrue(layers.all { it.luminous })
        assertEquals(SharedGroup.LAYERS, PresetPart.LAYERS.group)
        assertEquals(LoopTarget.entries.map { it.property }, layers)
    }

    @Test
    fun `a preset keeps Layer 2 with its part and only then`() {
        val dressed = Edits.setShared(looping, SharedProperty.LAYER_OPACITY, 0.8f)
        val kept = PresetRules.capture("Layered", dressed, setOf(PresetPart.LAYERS))
        assertEquals(dressed.layer, kept.layer)
        assertEquals(
            mapOf(SharedProperty.LAYER_OPACITY to 0.8f, SharedProperty.LAYER_INFLUENCE to 0.5f, SharedProperty.LAYER_SWAP to 0f),
            kept.shared,
        )
        assertNull(PresetRules.capture("Plain", dressed, setOf(PresetPart.CAMERA)).layer)
    }

    @Test
    fun `a kept Layer 2 applies on any Style, and goes off where it cannot be Layer 2`() {
        val kept = PresetRules.capture("Layered", looping, setOf(PresetPart.LAYERS))
        val onKaleido = PresetRules.apply(Fixtures.defaults(KALEIDO), kept, styles)
        assertEquals(looping.layer, onKaleido.layer)
        val onTunnel = PresetRules.apply(Fixtures.defaults(TUNNEL), kept, styles)
        assertEquals(looping.layer.copy(style = null), onTunnel.layer)
        val milk = kept.copy(layer = Layer(MILKDROP, loop = LayerLoop(depth = -1f)))
        assertEquals(Layer(loop = LayerLoop(depth = 0f)), PresetRules.apply(julia, milk, styles).layer)
        val withoutPart = kept.copy(parts = setOf(PresetPart.CAMERA))
        assertEquals(Layer.OFF, PresetRules.apply(julia, withoutPart, styles).layer)
    }

    @Test
    fun `only Styles that are not MilkDrop and not the Style itself can be Layer 2`() {
        assertTrue(Rules.canBeLayer(styles.manifestOf(TUNNEL), JULIA))
        assertFalse(Rules.canBeLayer(styles.manifestOf(TUNNEL), TUNNEL))
        assertFalse(Rules.canBeLayer(styles.manifestOf(MILKDROP), JULIA))
        assertNull(Rules.layerLoop(null))
        assertNull(Rules.layerLoop(LayerLoop(targets = emptySet())))
        val broken = LayerLoop(LoopShape.TRIANGLE, LoopRate.TWO_BEATS, Float.NaN)
        assertEquals(LayerLoop(LoopShape.TRIANGLE, LoopRate.TWO_BEATS, 0f), Rules.layerLoop(broken))
    }

    @Test
    fun `the loop presets and rates are what they say`() {
        assertEquals(setOf(LoopTarget.SWAP), LoopPreset.TRADE_PLACES.loop.targets)
        assertTrue(LoopRate.ONE_BAR.beatSynced)
        assertEquals(4, LoopRate.ONE_BAR.beats)
        assertFalse(LoopRate.EIGHT_SECONDS.beatSynced)
        assertEquals(8_000L, LoopRate.EIGHT_SECONDS.length.value)
        assertEquals(0.25f, LayerBlend.DIFFERENCE.maxOpacity)
        assertEquals(0.25f, LayerBlend.EXCLUSION.maxOpacity)
        assertEquals(1f, LayerBlend.ADD.maxOpacity)
        assertTrue(Layer(TUNNEL).on)
        assertFalse(Layer.OFF.on)
    }
}
