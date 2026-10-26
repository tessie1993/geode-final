package dev.geode.feature.customization.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.LayerBlend
import dev.geode.feature.customization.api.LayerInfluence
import dev.geode.feature.customization.api.LayerLoop
import dev.geode.feature.customization.api.LoopPreset
import dev.geode.feature.customization.api.LoopRate
import dev.geode.feature.customization.api.LoopShape
import dev.geode.feature.customization.api.LoopTarget
import dev.geode.feature.customization.api.SharedGroup
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.Target

/**
 * The Layers section: Layer 2's Style (or Off), its blend as chips with plain names, its opacity, its
 * influence and amount, the swap, and its loop with its shape, rate, depth and what it moves. Built from
 * the shared Customize controls only, so it follows the App UI's look wherever that goes.
 */
@Composable
internal fun LayersSection(panel: PanelState) {
    val model = panel.model
    val layer = model.layer
    SectionTitle(stringResource(R.string.customize_layer_style))
    ChipFlow {
        ChoiceChip(stringResource(R.string.customize_layer_off), !layer.on) {
            panel.send(CustomizationCommand.ChooseLayerStyle(null))
        }
        model.layerLooks.forEach { look ->
            ChoiceChip(look.name, look.style == layer.style) { panel.send(CustomizationCommand.ChooseLayerStyle(look.style)) }
        }
    }
    if (model.someLookCannotBeLayer) QuietText(stringResource(R.string.customize_layer_milkdrop))
    if (!layer.on) {
        QuietText(stringResource(R.string.customize_layer_off_hint))
        return
    }

    SectionTitle(stringResource(R.string.customize_layer_blend))
    ChipFlow {
        LayerBlend.entries.forEach { blend ->
            ChoiceChip(blendName(blend), blend == layer.blend) { panel.send(CustomizationCommand.SetLayerBlend(blend)) }
        }
    }
    if (layer.blend.maxOpacity < 1f) QuietText(stringResource(R.string.customize_layer_capped, blendName(layer.blend)))
    LayerSliders(panel, SharedProperty.LAYER_OPACITY, SharedProperty.LAYER_SWAP)
    QuietText(stringResource(R.string.customize_layer_swap_hint))

    SectionTitle(stringResource(R.string.customize_layer_influence))
    ChipFlow {
        LayerInfluence.entries.forEach { influence ->
            ChoiceChip(influenceName(influence), influence == layer.influence) {
                panel.send(CustomizationCommand.SetLayerInfluence(influence))
            }
        }
    }
    if (layer.influence != LayerInfluence.NONE) LayerSliders(panel, SharedProperty.LAYER_INFLUENCE)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(stringResource(R.string.customize_layer_loop), Modifier.weight(1f))
        Switch(checked = layer.loop != null, onCheckedChange = { panel.send(loopToggled(layer)) })
    }
    layer.loop?.let { LoopControls(panel, it) }
}

/** Layer 2's own sliders, from the Shared core rows the model already has (Learn mode and "React to…" included). */
@Composable
private fun LayerSliders(
    panel: PanelState,
    vararg properties: SharedProperty,
) {
    val wanted = properties.map { Target.Shared(it) }
    val rows = panel.model.shared[SharedGroup.LAYERS].orEmpty().filter { it.target in wanted }
    SliderGroup(panel, rows)
}

@Composable
private fun LoopControls(
    panel: PanelState,
    loop: LayerLoop,
) {
    val set = { next: LayerLoop -> panel.send(CustomizationCommand.SetLayerLoop(next)) }
    QuietText(stringResource(R.string.customize_layer_loop_ready))
    ChipFlow {
        LoopPreset.entries.forEach { preset ->
            ChoiceChip(loopPresetName(preset), preset.loop == loop) { set(preset.loop) }
        }
    }
    QuietText(stringResource(R.string.customize_layer_loop_shape))
    ChipFlow {
        LoopShape.entries.forEach { shape -> ChoiceChip(loopShapeName(shape), shape == loop.shape) { set(loop.copy(shape = shape)) } }
    }
    QuietText(stringResource(R.string.customize_layer_loop_rate))
    ChipFlow {
        LoopRate.entries.forEach { rate -> ChoiceChip(loopRateName(rate), rate == loop.rate) { set(loop.copy(rate = rate)) } }
    }
    QuietText(stringResource(R.string.customize_layer_loop_moves))
    ChipFlow {
        LoopTarget.entries.forEach { target ->
            ChoiceChip(loopTargetName(target), target in loop.targets) { loop.toggled(target)?.let(panel.send) }
        }
    }
    HeldSlider(
        label = stringResource(R.string.customize_layer_loop_depth),
        value = loop.depth,
        range = 0f..1f,
        text = { stringResource(R.string.customize_percent, percentOf(it)) },
        onSet = { set(loop.copy(depth = it)) },
    )
}

/** The basic view's way into the Layers section: what Layer 2 shows now, one tap from its controls. */
@Composable
internal fun LayersEntry(panel: PanelState) {
    val name = panel.model.layerName ?: stringResource(R.string.customize_layer_off)
    SectionTitle(stringResource(R.string.customize_layers))
    ChoiceChip(stringResource(R.string.customize_layer_summary, name), panel.model.layer.on) {
        panel.open(CustomizePage.Advanced(AdvancedSection.LAYERS))
    }
}
