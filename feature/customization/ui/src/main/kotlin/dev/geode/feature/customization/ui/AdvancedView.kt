package dev.geode.feature.customization.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.geode.design.forest.ForestShapes
import dev.geode.design.forest.ForestSpacing
import dev.geode.design.forest.LocalForestColors
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.MacroLink
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.SharedGroup
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.StartMode
import dev.geode.feature.customization.api.Wave
import kotlin.math.roundToInt

/**
 * The Advanced view (#12): its sections as tabs, then the open one. Reset to Style sits here, away
 * from the knobs, since it undoes every edit at once (it can itself be undone).
 */
@Composable
internal fun AdvancedView(
    panel: PanelState,
    page: CustomizePage.Advanced,
) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = ForestSpacing.ExtraSmall),
        horizontalArrangement = Arrangement.spacedBy(ForestSpacing.Small),
    ) {
        AdvancedSection.entries.forEach { section ->
            ChoiceChip(sectionName(section), section == page.section) { panel.open(CustomizePage.Advanced(section)) }
        }
    }
    when (page.section) {
        AdvancedSection.REACTIONS -> ReactionsSection(panel, page)
        AdvancedSection.MODULATORS -> ModulatorsSection(panel)
        AdvancedSection.CAMERA -> SliderGroup(panel, panel.model.shared.getValue(SharedGroup.CAMERA))
        AdvancedSection.EFFECTS ->
            panel.model.shared.filterKeys { it != SharedGroup.CAMERA }.forEach { (group, rows) ->
                SectionTitle(groupName(group))
                SliderGroup(panel, rows)
            }
        AdvancedSection.EXTRAS ->
            if (panel.model.extras.isEmpty()) {
                QuietText(stringResource(R.string.customize_no_extras))
            } else {
                SliderGroup(panel, panel.model.extras)
            }
        AdvancedSection.MACROS -> MacrosSection(panel, page.macro)
    }
    TextButton(onClick = { panel.send(CustomizationCommand.ResetToStyle) }, modifier = Modifier.padding(top = ForestSpacing.Large)) {
        Text(stringResource(R.string.customize_reset))
    }
}

@Composable
internal fun sectionName(section: AdvancedSection): String =
    stringResource(
        when (section) {
            AdvancedSection.REACTIONS -> R.string.customize_section_reactions
            AdvancedSection.MODULATORS -> R.string.customize_section_modulators
            AdvancedSection.CAMERA -> R.string.customize_section_camera
            AdvancedSection.EFFECTS -> R.string.customize_section_effects
            AdvancedSection.EXTRAS -> R.string.customize_section_extras
            AdvancedSection.MACROS -> R.string.customize_section_macros
        },
    )

@Composable
internal fun QuietText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = LocalForestColors.current.textSecondary,
        modifier = Modifier.padding(vertical = ForestSpacing.Small),
    )
}

/** Sliders that set their value, link in Learn mode, and offer "React to…" on a long-press. */
@Composable
private fun SliderGroup(
    panel: PanelState,
    rows: List<SliderRow>,
) {
    rows.forEach { row ->
        ValueSlider(
            row = row,
            label = targetName(row.target, panel.model),
            onSet = { panel.slide(row.target, it) },
            onReactTo = { panel.open(CustomizePage.ChooseSource(target = row.target)) },
        )
    }
}

/**
 * The link list, grouped by target, each link with its live meter: tap to edit, swipe to remove. A
 * toggle switches to the patch-cable board, which shows every link, loops included.
 */
@Composable
private fun ReactionsSection(
    panel: PanelState,
    page: CustomizePage.Advanced,
) {
    val board = page.board
    val model = panel.model
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ForestSpacing.Small)) {
        ChoiceChip(stringResource(R.string.customize_links_list), !board) { panel.open(page.copy(board = false)) }
        ChoiceChip(stringResource(R.string.customize_links_board), board) { panel.open(page.copy(board = true)) }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { panel.open(CustomizePage.ChooseSource(learn = true)) }) {
            Text(stringResource(R.string.customize_learn))
        }
        IconButton(onClick = { panel.open(CustomizePage.ChooseSource()) }) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.customize_new_link))
        }
    }
    when {
        model.reactions.isEmpty() -> QuietText(stringResource(R.string.customize_no_links))
        board -> PatchBoard(model)
        else ->
            model.reactions.forEach { group ->
                SectionTitle(targetName(group.target, model))
                group.links.forEach { link -> LinkItem(panel, link) }
            }
    }
}

@Composable
private fun LinkItem(
    panel: PanelState,
    link: LinkRow,
) {
    val reaction = link.reaction
    val remove = { panel.send(CustomizationCommand.RemoveReaction(reaction.id)) }
    SwipeToRemove(onRemove = remove) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { panel.open(CustomizePage.EditReaction(reaction, isNew = false)) }
                .padding(vertical = ForestSpacing.ExtraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row {
                    Text(sourceName(reaction.source, panel.model), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(ForestSpacing.Small))
                    Text(
                        if (reaction.invert) {
                            stringResource(R.string.customize_link_inverted)
                        } else {
                            stringResource(R.string.customize_percent, percentOf(reaction.amount))
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = LocalForestColors.current.textSecondary,
                    )
                }
                Meter(link.live ?: reaction.amount, Modifier.padding(top = ForestSpacing.ExtraSmall))
            }
            IconButton(onClick = remove) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.customize_remove_link))
            }
        }
    }
}

/** Modulators: add one of the four kinds; each shows its picture, its start setting, rate and depth. */
@Composable
private fun ModulatorsSection(panel: PanelState) {
    ChipFlow {
        ModulatorKind.entries.forEach { kind ->
            AssistChip(
                onClick = { panel.send(CustomizationCommand.AddModulator(newModulator(kind))) },
                label = { Text(kindName(kind)) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.customize_add_modulator)) },
                shape = ForestShapes.Chip,
            )
        }
    }
    if (panel.model.modulators.isEmpty()) QuietText(stringResource(R.string.customize_no_modulators))
    panel.model.modulators.forEach { ModulatorCard(panel, it) }
}

@Composable
private fun ModulatorCard(
    panel: PanelState,
    row: ModulatorRow,
) {
    val modulator = row.modulator
    val update = { changed: Modulator -> panel.send(CustomizationCommand.UpdateModulator(changed)) }
    InsetCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(modulatorName(modulator.id, panel.model), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (modulator.start == StartMode.ByHand) {
                IconButton(onClick = { panel.send(togglePlay(modulator)) }) {
                    if (modulator.playing) {
                        Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.customize_stop))
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.customize_play))
                    }
                }
            }
            IconButton(onClick = { panel.send(CustomizationCommand.RemoveModulator(modulator.id)) }) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.customize_remove_modulator))
            }
        }
        if (!row.linked) QuietText(stringResource(R.string.customize_modulator_idle))
        ModulatorPicture(modulator.shape, Modifier.padding(vertical = ForestSpacing.Small))
        val shape = modulator.shape
        when (shape) {
            is ModulatorShape.Lfo -> {
                ChipFlow {
                    Wave.entries.forEach { wave ->
                        ChoiceChip(waveName(wave), shape.wave == wave) { update(modulator.copy(shape = shape.copy(wave = wave))) }
                    }
                }
                BeatSync(shape.beatSync) { update(modulator.copy(shape = shape.copy(beatSync = it))) }
            }
            is ModulatorShape.StepSequencer ->
                BeatSync(shape.beatSync) { update(modulator.copy(shape = shape.copy(beatSync = it))) }
            is ModulatorShape.HitEnvelope, is ModulatorShape.RandomDrift -> Unit
        }
        SectionTitle(stringResource(R.string.customize_start))
        ChipFlow {
            ChoiceChip(stringResource(R.string.customize_start_playing), modulator.start == StartMode.WhilePlaying) {
                update(modulator.copy(start = StartMode.WhilePlaying))
            }
            ChoiceChip(stringResource(R.string.customize_start_sound), modulator.start is StartMode.OnSound) {
                update(modulator.copy(start = StartMode.OnSound(SoundSignal.KICK)))
            }
            ChoiceChip(stringResource(R.string.customize_start_hand), modulator.start == StartMode.ByHand) {
                update(modulator.copy(start = StartMode.ByHand))
            }
        }
        HeldSlider(
            label = stringResource(R.string.customize_rate),
            value = modulator.rate,
            range = Modulator.MIN_RATE..Modulator.MAX_RATE,
            text = { stringResource(R.string.customize_rate_value, it) },
            onSet = { update(modulator.copy(rate = it)) },
        )
        HeldSlider(
            label = stringResource(R.string.customize_depth),
            value = modulator.depth,
            range = 0f..1f,
            text = { stringResource(R.string.customize_percent, percentOf(it)) },
            onSet = { update(modulator.copy(depth = it)) },
        )
    }
}

/** Whether a wave or a sequencer follows the beat instead of its own period. */
@Composable
private fun BeatSync(
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.customize_beat_sync), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** A labelled slider that follows the finger and sets its value when let go: one drag, one undo step. */
@Composable
internal fun HeldSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    text: @Composable (Float) -> String,
    onSet: (Float) -> Unit,
) {
    var held by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(text(held), style = MaterialTheme.typography.labelMedium, color = LocalForestColors.current.textSecondary)
    }
    Slider(value = held, onValueChange = { held = it }, valueRange = range, onValueChangeFinished = { onSet(held) })
}

/**
 * The Macros: rename, set, clear and remap each one. Opened on [focused] from a long-press on its knob,
 * which then shows that Macro alone.
 */
@Composable
private fun MacrosSection(
    panel: PanelState,
    focused: Int?,
) {
    val macros = panel.model.macros
    if (focused != null) {
        ChoiceChip(stringResource(R.string.customize_all_macros), selected = false) {
            panel.open(CustomizePage.Advanced(AdvancedSection.MACROS))
        }
    }
    macros.filter { focused == null || it.index == focused }.forEach { MacroCard(panel, it) }
}

@Composable
private fun MacroCard(
    panel: PanelState,
    knob: MacroKnob,
) {
    val model = panel.model
    var name by remember(knob.name) { mutableStateOf(knob.name) }
    InsetCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.customize_macro_name)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { panel.send(CustomizationCommand.RenameMacro(knob.index, name.trim())) },
                enabled = name.isNotBlank() && name.trim() != knob.name,
            ) {
                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.customize_rename))
            }
        }
        HeldSlider(
            label = stringResource(R.string.customize_macro_value),
            value = knob.value,
            range = 0f..1f,
            text = { stringResource(R.string.customize_percent, percentOf(it)) },
            onSet = { panel.send(CustomizationCommand.SetMacro(knob.index, it)) },
        )
        knob.drivers.forEach { driver ->
            QuietText(stringResource(R.string.customize_macro_driven_by, sourceName(driver, model)))
        }
        SectionTitle(stringResource(R.string.customize_macro_links))
        if (knob.links.isEmpty()) QuietText(stringResource(R.string.customize_macro_no_links))
        knob.links.forEach { link -> MacroLinkItem(panel, knob.index, link) }
        Row {
            TextButton(onClick = { panel.open(CustomizePage.ChooseTarget(macro = knob.index)) }) {
                Text(stringResource(R.string.customize_macro_add_link))
            }
            TextButton(onClick = { panel.send(CustomizationCommand.ClearMacro(knob.index)) }, enabled = knob.links.isNotEmpty()) {
                Text(stringResource(R.string.customize_macro_clear))
            }
        }
    }
}

/** One property a Macro moves, with the range it moves it over. */
@Composable
private fun MacroLinkItem(
    panel: PanelState,
    index: Int,
    link: MacroLink,
) {
    val name = targetName(link.target, panel.model)
    var held by remember(link) { mutableStateOf(minOf(link.low, link.high)..maxOf(link.low, link.high)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.customize_range_value, percentOf(link.low), percentOf(link.high)),
            style = MaterialTheme.typography.labelMedium,
            color = LocalForestColors.current.textSecondary,
        )
        IconButton(onClick = { panel.send(CustomizationCommand.UnlinkMacro(index, link.target)) }) {
            Icon(Icons.Filled.LinkOff, contentDescription = stringResource(R.string.customize_macro_unlink, name))
        }
    }
    RangeSlider(
        value = held,
        onValueChange = { held = it },
        onValueChangeFinished = {
            // A link turned down as the Macro goes up keeps its direction when its range is moved.
            val reversed = link.low > link.high
            val low = if (reversed) held.endInclusive else held.start
            val high = if (reversed) held.start else held.endInclusive
            panel.send(CustomizationCommand.LinkMacro(index, link.copy(low = low, high = high)))
        },
    )
}

/** A quiet card inside the panel, for one Modulator or one Macro. Never glass on glass: it only darkens. */
@Composable
private fun InsetCard(content: @Composable () -> Unit) {
    Surface(
        shape = ForestShapes.Card,
        color = LocalForestColors.current.base.copy(alpha = QUIET_ALPHA),
        modifier = Modifier.fillMaxWidth().padding(vertical = ForestSpacing.Small),
    ) {
        Column(Modifier.padding(ForestSpacing.Medium)) { content() }
    }
}

/** [fraction] 0..1 as a whole percentage. */
internal fun percentOf(fraction: Float): Int = (fraction * 100).roundToInt()
