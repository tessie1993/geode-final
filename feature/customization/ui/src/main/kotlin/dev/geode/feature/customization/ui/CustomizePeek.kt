package dev.geode.feature.customization.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import dev.geode.design.glass.LocalGlassStyle
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.ValueKind
import kotlinx.coroutines.launch

// The peek bar of a lowered Customize panel (#412): the control touched last, to nudge while
// watching the Visual.

/** The control [command] touches, when it sets or resets one value; null for anything else. */
internal fun touchedTarget(command: CustomizationCommand): Target? =
    when (command) {
        is CustomizationCommand.SetShared -> Target.Shared(command.property)
        is CustomizationCommand.SetExtra -> Target.Extra(command.kind)
        is CustomizationCommand.SetMacro -> Target.MacroSlot(command.index)
        is CustomizationCommand.ResetValue -> command.target
        is CustomizationCommand.Preview -> touchedTarget(command.edit)
        else -> null
    }

/**
 * The row [target] shows as in [model]: a Shared core property or an Extra as the Advanced view shows
 * it, a Macro as 0..1. Null for a Modulator's field, which has no slider here, or a control [model]
 * no longer has.
 */
internal fun peekRow(
    model: CustomizeModel,
    target: Target,
): SliderRow? =
    when (target) {
        is Target.Shared -> model.shared.values.firstNotNullOfOrNull { rows -> rows.firstOrNull { it.target == target } }
        is Target.Extra -> model.extras.firstOrNull { it.target == target }
        is Target.MacroSlot ->
            model.macros.getOrNull(target.index)?.let { knob ->
                SliderRow(target, knob.value, 0f, 1f, ValueKind.CONTINUOUS, knob.drivers.isNotEmpty(), ignored = knob.ignored)
            }
        is Target.ModulatorField -> null
    }

/**
 * What the peek bar holds: the control [touched] last, live as in the panel, or until one is touched
 * a line saying the changes show on the Visual. "React to…" on it brings the panel back to link it.
 */
@Composable
internal fun PeekControl(
    panel: PanelState,
    touched: Target?,
) {
    val row = touched?.let { peekRow(panel.model, it) }
    val scope = rememberCoroutineScope()
    if (row == null) {
        Text(
            stringResource(R.string.customize_peek_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalGlassStyle.current.colors.textPrimary,
        )
    } else {
        ValueSlider(
            row = row,
            label = targetName(row.target, panel.model),
            onSet = { panel.slide(row.target, it) },
            onPreview = { panel.preview(row.target, it) },
            onReset = { panel.send(CustomizationCommand.ResetValue(row.target)) },
            onReactTo = {
                panel.open(CustomizePage.ChooseSource(target = row.target))
                scope.launch { panel.peek.expand() }
            },
        )
    }
}
