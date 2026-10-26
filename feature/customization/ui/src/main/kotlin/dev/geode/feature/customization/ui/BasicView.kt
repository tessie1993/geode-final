package dev.geode.feature.customization.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.geode.design.forest.ForestSpacing
import dev.geode.design.forest.LocalForestColors
import dev.geode.feature.customization.api.CompareSlot
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.SharedProperty

/** The Macro knobs sit in rows of this many: a 3×2 grid (#12). */
private const val KNOBS_PER_ROW = 3

/**
 * The basic view (#12): the 6 Macro knobs, the effect toggles, the way into Layer 2, the palette chips,
 * this Style's presets and the look picker strip.
 */
@Composable
internal fun BasicView(panel: PanelState) {
    val model = panel.model
    SectionTitle(stringResource(R.string.customize_macros))
    model.macros.chunked(KNOBS_PER_ROW).forEach { row ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = ForestSpacing.ExtraSmall),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            row.forEach { knob ->
                MacroKnobView(
                    knob = knob,
                    driver = driverTag(knob, model),
                    onSet = { panel.send(CustomizationCommand.SetMacro(knob.index, it)) },
                    onEdit = { panel.open(CustomizePage.Advanced(AdvancedSection.MACROS, knob.index)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    SectionTitle(stringResource(R.string.customize_effects))
    ChipFlow {
        model.effects.forEach { effect ->
            ChoiceChip(propertyName(effect.property), effect.on) { panel.send(effect.toggled()) }
        }
    }

    LayersEntry(panel)

    SectionTitle(stringResource(R.string.customize_palettes))
    ChipStrip {
        val palette = SharedProperty.PALETTE
        for (index in palette.min.toInt()..palette.max.toInt()) {
            ChoiceChip(stringResource(R.string.customize_whole_value, index + 1), index == model.palette) {
                panel.send(CustomizationCommand.SetShared(palette, index.toFloat()))
            }
        }
    }

    // A/B compare: keep what is on screen in a slot, then switch between the two while deciding.
    SectionTitle(stringResource(R.string.customize_compare))
    ChipStrip {
        CompareSlot.entries.forEach { slot ->
            ChoiceChip(stringResource(R.string.customize_keep_slot, slot.name), selected = false) {
                panel.send(CustomizationCommand.KeepForCompare(slot))
            }
            if (slot in model.kept) {
                ChoiceChip(stringResource(R.string.customize_show_slot, slot.name), selected = false) {
                    panel.send(CustomizationCommand.Compare(slot))
                }
            }
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        SectionTitle(stringResource(R.string.customize_presets), Modifier.weight(1f))
        // The way into the Presets browser (#144): every built-in and saved preset, by folder.
        TextButton(onClick = { panel.onAction(CustomizeAction.BrowsePresets) }) { Text(stringResource(R.string.customize_all_presets)) }
    }
    if (model.presets.isEmpty()) {
        Text(
            stringResource(R.string.customize_no_presets),
            style = MaterialTheme.typography.bodySmall,
            color = LocalForestColors.current.textSecondary,
        )
    } else {
        ChipStrip {
            model.presets.forEach { preset ->
                ChoiceChip(preset.name, selected = false) { panel.send(CustomizationCommand.LoadPreset(preset.id)) }
            }
        }
    }

    if (model.looks.isNotEmpty()) {
        SectionTitle(stringResource(R.string.customize_looks))
        ChipStrip {
            model.looks.forEach { look ->
                ChoiceChip(look.name, model.isCurrent(look)) { panel.send(CustomizationCommand.ChooseStyle(look.style)) }
            }
        }
    }
}

/** A single scrolling line of chips. */
@Composable
private fun ChipStrip(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(ForestSpacing.Small),
    ) { content() }
}

/** The small tag under a knob naming what drives it, such as "Bass", or "Bass +1" when more than one does. */
@Composable
private fun driverTag(
    knob: MacroKnob,
    model: CustomizeModel,
): String? {
    val first = knob.drivers.firstOrNull() ?: return null
    val name = sourceName(first, model)
    val others = knob.drivers.size - 1
    return if (others == 0) name else stringResource(R.string.customize_driver_more, name, others)
}
