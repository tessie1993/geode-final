package dev.geode.feature.customization.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.geode.design.glass.GlassSpacing
import dev.geode.engine.types.Millis
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.PresetPart
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.ResponseCurve
import dev.geode.feature.customization.api.SharedGroup
import dev.geode.feature.customization.api.Target
import kotlin.math.roundToInt

/**
 * Step 1 of a new link: what it reacts to, a Sound signal or a Modulator. In Learn mode the chosen
 * source waits for the next slider moved; from "React to…" the target is already known.
 */
@Composable
internal fun ChooseSource(
    panel: PanelState,
    page: CustomizePage.ChooseSource,
) {
    val model = panel.model
    val target = page.target
    Text(
        stringResource(if (page.learn) R.string.customize_step_learn else R.string.customize_step_source),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = GlassSpacing.Small),
    )
    val pick = { source: ReactionSource ->
        when {
            page.learn -> {
                panel.learn(source)
                panel.open(CustomizePage.Advanced(AdvancedSection.CAMERA))
            }
            target != null -> panel.open(CustomizePage.EditReaction(newReaction(source, target), isNew = true))
            else -> panel.open(CustomizePage.ChooseTarget(source = source))
        }
    }
    val (sounds, generated) = model.sources.partition { it is ReactionSource.Sound }
    SectionTitle(stringResource(R.string.customize_sources_sound))
    ChipFlow { sounds.forEach { source -> ChoiceChip(sourceName(source, model), selected = false) { pick(source) } } }
    if (generated.isNotEmpty()) {
        SectionTitle(stringResource(R.string.customize_sources_modulators))
        ChipFlow { generated.forEach { source -> ChoiceChip(sourceName(source, model), selected = false) { pick(source) } } }
    }
    CancelButton(panel)
}

/** Step 2 of a new link: what it moves, grouped as the Advanced view groups its sliders; or a Macro's new property. */
@Composable
internal fun ChooseTarget(
    panel: PanelState,
    page: CustomizePage.ChooseTarget,
) {
    val model = panel.model
    Text(
        stringResource(if (page.macro != null) R.string.customize_step_macro_target else R.string.customize_step_target),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = GlassSpacing.Small),
    )
    val pick = { target: Target ->
        val macro = page.macro
        val source = page.source
        if (macro != null) {
            panel.send(linkMacro(macro, target))
            panel.open(CustomizePage.Advanced(AdvancedSection.MACROS, macro))
        } else if (source != null) {
            panel.open(CustomizePage.EditReaction(newReaction(source, target), isNew = true))
        }
    }
    // A Macro never moves itself.
    val targets = model.targets.filterNot { page.macro != null && it == Target.MacroSlot(page.macro) }
    targetGroups(targets).forEach { (title, group) ->
        SectionTitle(title)
        ChipFlow { group.forEach { target -> ChoiceChip(targetName(target, model), selected = false) { pick(target) } } }
    }
    CancelButton(panel)
}

/** [targets] under the headings the picker shows them with, empty headings left out. */
@Composable
private fun targetGroups(targets: List<Target>): List<Pair<String, List<Target>>> {
    val shared =
        SharedGroup.entries.map { group ->
            groupName(group) to targets.filter { it is Target.Shared && it.property.group == group }
        }
    return (
        shared +
            listOf(
                stringResource(R.string.customize_targets_extras) to targets.filterIsInstance<Target.Extra>(),
                stringResource(R.string.customize_targets_macros) to targets.filterIsInstance<Target.MacroSlot>(),
                stringResource(R.string.customize_targets_modulators) to targets.filterIsInstance<Target.ModulatorField>(),
            )
    ).filter { it.second.isNotEmpty() }
}

/**
 * Shaping a Reaction (#11): amount and direction, rise and fall, range and curve. A new link is added
 * on Done; an existing one is replaced, or removed.
 */
@Composable
internal fun ReactionEditor(
    panel: PanelState,
    page: CustomizePage.EditReaction,
) {
    val model = panel.model
    var draft by remember(page.reaction) { mutableStateOf(page.reaction) }
    val reaction = draft
    Text(
        stringResource(if (page.isNew) R.string.customize_step_shape else R.string.customize_edit_link),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = GlassSpacing.Small),
    )
    Text(
        stringResource(R.string.customize_link_path, sourceName(reaction.source, model), targetName(reaction.target, model)),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(vertical = GlassSpacing.Small),
    )
    HeldSlider(
        label = stringResource(R.string.customize_amount),
        value = reaction.amount,
        range = 0f..1f,
        text = { stringResource(R.string.customize_percent, percentOf(it)) },
        onSet = { draft = draft.copy(amount = it) },
        help = stringResource(R.string.customize_help_amount),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.customize_invert), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = reaction.invert, onCheckedChange = { draft = draft.copy(invert = it) })
    }
    HelpLine(stringResource(R.string.customize_help_invert))
    HeldSlider(
        label = stringResource(R.string.customize_rise),
        value = reaction.rise.value.toFloat(),
        range = 0f..MAX_RESPONSE_MILLIS.toFloat(),
        text = { stringResource(R.string.customize_millis, it.roundToInt()) },
        onSet = { draft = draft.copy(rise = Millis(it.roundToInt().toLong())) },
        help = stringResource(R.string.customize_help_rise),
    )
    HeldSlider(
        label = stringResource(R.string.customize_fall),
        value = reaction.fall.value.toFloat(),
        range = 0f..MAX_RESPONSE_MILLIS.toFloat(),
        text = { stringResource(R.string.customize_millis, it.roundToInt()) },
        onSet = { draft = draft.copy(fall = Millis(it.roundToInt().toLong())) },
        help = stringResource(R.string.customize_help_fall),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.customize_range), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.customize_range_value, percentOf(reaction.low), percentOf(reaction.high)),
            style = MaterialTheme.typography.labelMedium,
        )
    }
    HelpLine(stringResource(R.string.customize_help_range))
    RangeSlider(
        value = reaction.low..reaction.high,
        onValueChange = { draft = draft.copy(low = it.start, high = it.endInclusive) },
    )
    SectionTitle(stringResource(R.string.customize_curve))
    ChipFlow {
        ResponseCurve.entries.forEach { curve ->
            ChoiceChip(curveName(curve), reaction.curve == curve) { draft = draft.copy(curve = curve) }
        }
    }
    if (isLuminous(reaction.target)) QuietText(stringResource(R.string.customize_no_flash))
    Row(
        Modifier.fillMaxWidth().padding(top = GlassSpacing.Medium),
        horizontalArrangement = Arrangement.spacedBy(GlassSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!page.isNew) {
            TextButton(
                onClick = {
                    panel.send(CustomizationCommand.RemoveReaction(reaction.id))
                    panel.open(back(page))
                },
            ) { Text(stringResource(R.string.customize_remove)) }
        }
        TextButton(onClick = { panel.open(back(page)) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.customize_cancel))
        }
        Button(
            onClick = {
                panel.send(saveReaction(reaction, page.isNew))
                panel.open(back(page))
            },
        ) { Text(stringResource(R.string.customize_done)) }
    }
}

/** Whether a Reaction on [target] moves overall brightness, which the No-flash rule slows down. */
private fun isLuminous(target: Target): Boolean = target is Target.Shared && target.property.luminous

/** The "choose which parts" save (#12): a name, and the parts to keep, every one ticked to begin with. */
@Composable
internal fun SavePresetPage(panel: PanelState) {
    var name by remember { mutableStateOf("") }
    var parts by remember { mutableStateOf(DEFAULT_PRESET_PARTS) }
    Text(
        stringResource(R.string.customize_save_title),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = GlassSpacing.Small),
    )
    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.customize_save_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    SectionTitle(stringResource(R.string.customize_save_parts))
    ChipFlow {
        PresetPart.entries.forEach { part ->
            ChoiceChip(partName(part), part in parts) { parts = if (part in parts) parts - part else parts + part }
        }
    }
    val save = savePreset(name, parts)
    Row(
        Modifier.fillMaxWidth().padding(top = GlassSpacing.Medium),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = { panel.open(CustomizePage.Basic) }) { Text(stringResource(R.string.customize_cancel)) }
        Button(
            onClick = {
                save?.let(panel.send)
                panel.open(CustomizePage.Basic)
            },
            enabled = save != null,
        ) { Text(stringResource(R.string.customize_save_confirm)) }
    }
}

@Composable
private fun CancelButton(panel: PanelState) {
    TextButton(onClick = { panel.open(back(panel.page)) }, modifier = Modifier.padding(top = GlassSpacing.Medium)) {
        Text(stringResource(R.string.customize_cancel))
    }
}
