package dev.geode.feature.customization.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.geode.design.forest.ForestGlass
import dev.geode.design.forest.ForestShapes
import dev.geode.design.forest.ForestSpacing
import dev.geode.design.forest.LocalForestColors
import dev.geode.feature.customization.api.Automations
import dev.geode.feature.customization.api.Cadence
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.SharedGroup
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.StartMode
import dev.geode.feature.customization.api.StylePool
import dev.geode.feature.customization.api.StyleSwitching
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.Wave

/**
 * The Visuals tab's "Modulators & Automation" sub-tab (#41): the Modulators, then what the Customization
 * performs by itself over the music. Stateless (ADR-0003): it shows [model] and reports everything to
 * [onAction]. The three sub-tabs are put together where the routes are wired (#17).
 */
@Composable
fun ModulatorsAutomationScreen(
    model: CustomizeModel,
    onAction: (CustomizeAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val send = { command: CustomizationCommand -> onAction(CustomizeAction.Send(command)) }
    val colors = LocalForestColors.current
    Surface(
        shape = ForestShapes.Sheet,
        color = ForestGlass.textGlass(colors),
        contentColor = colors.textPrimary,
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ForestSpacing.Large, vertical = ForestSpacing.Medium),
        ) {
            Text(stringResource(R.string.modulators_automation_title), style = MaterialTheme.typography.headlineMedium)
            SectionTitle(stringResource(R.string.customize_section_modulators))
            ModulatorsSection(model, send)
            SectionTitle(stringResource(R.string.automation_section))
            AutomationsSection(model) { send(CustomizationCommand.SetAutomations(it)) }
        }
    }
}

/** Modulators: add one of the four kinds; each shows its picture, its start setting, rate and depth. */
@Composable
private fun ModulatorsSection(
    model: CustomizeModel,
    send: (CustomizationCommand) -> Unit,
) {
    ChipFlow {
        ModulatorKind.entries.forEach { kind ->
            AssistChip(
                onClick = { send(CustomizationCommand.AddModulator(newModulator(kind))) },
                label = { Text(kindName(kind)) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.customize_add_modulator)) },
                shape = ForestShapes.Chip,
            )
        }
    }
    if (model.modulators.isEmpty()) QuietText(stringResource(R.string.customize_no_modulators))
    model.modulators.forEach { ModulatorCard(model, it, send) }
}

@Composable
private fun ModulatorCard(
    model: CustomizeModel,
    row: ModulatorRow,
    send: (CustomizationCommand) -> Unit,
) {
    val modulator = row.modulator
    val update = { changed: Modulator -> send(CustomizationCommand.UpdateModulator(changed)) }
    InsetCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(modulatorName(modulator.id, model), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (modulator.start == StartMode.ByHand) {
                IconButton(onClick = { send(togglePlay(modulator)) }) {
                    if (modulator.playing) {
                        Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.customize_stop))
                    } else {
                        Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.customize_play))
                    }
                }
            }
            IconButton(onClick = { send(CustomizationCommand.RemoveModulator(modulator.id)) }) {
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
                SwitchRow(stringResource(R.string.customize_beat_sync), shape.beatSync) {
                    update(modulator.copy(shape = shape.copy(beatSync = it)))
                }
            }
            is ModulatorShape.StepSequencer ->
                SwitchRow(stringResource(R.string.customize_beat_sync), shape.beatSync) {
                    update(modulator.copy(shape = shape.copy(beatSync = it)))
                }
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

/**
 * The Automations: Style switching, palette changes, Camera moves on the beat, sequences and autopilot
 * Macros. Each is off until turned on; every change is one undo step.
 */
@Composable
private fun AutomationsSection(
    model: CustomizeModel,
    set: (Automations) -> Unit,
) {
    val automations = model.automations
    InsetCard {
        SwitchRow(stringResource(R.string.automation_style), automations.style != null) { set(automations.switchingStyle(it)) }
        automations.style?.let { switching -> StyleSwitchingControls(model, switching) { set(automations.copy(style = it)) } }
    }
    InsetCard {
        SwitchRow(stringResource(R.string.automation_palette), automations.palette != null) { set(automations.changingPalette(it)) }
        automations.palette?.let { palette ->
            CadenceChips(palette.cadence) { set(automations.copy(palette = palette.copy(cadence = it))) }
        }
    }
    InsetCard {
        Text(stringResource(R.string.automation_camera), style = MaterialTheme.typography.bodyMedium)
        ChipFlow {
            model.shared.getValue(SharedGroup.CAMERA).mapNotNull { (it.target as? Target.Shared)?.property }.forEach { property ->
                ChoiceChip(propertyName(property), automations.camera.any { it.property == property }) {
                    set(automations.toggledCamera(property))
                }
            }
        }
    }
    InsetCard {
        Text(stringResource(R.string.automation_sequences), style = MaterialTheme.typography.bodyMedium)
        QuietText(stringResource(R.string.automation_sequences_hint))
        ChipFlow {
            model.sequenceTargets().forEach { target ->
                ChoiceChip(targetName(target, model), automations.sequences.any { it.target == target }) {
                    set(automations.toggledSequence(target))
                }
            }
        }
    }
    InsetCard {
        Text(stringResource(R.string.automation_autopilot), style = MaterialTheme.typography.bodyMedium)
        QuietText(stringResource(R.string.automation_autopilot_hint))
        ChipFlow {
            model.macros.forEach { knob ->
                ChoiceChip(knob.name, automations.autopilots.any { it.macro == knob.index }) {
                    set(automations.toggledAutopilot(knob.index))
                }
            }
        }
    }
}

@Composable
private fun StyleSwitchingControls(
    model: CustomizeModel,
    switching: StyleSwitching,
    set: (StyleSwitching) -> Unit,
) {
    CadenceChips(switching.cadence) { set(switching.copy(cadence = it)) }
    ChipFlow {
        StylePool.entries.forEach { pool -> ChoiceChip(poolName(pool), switching.pool == pool) { set(switching.copy(pool = pool)) } }
    }
    if (switching.pool != StylePool.PICKS) {
        SectionTitle(stringResource(R.string.automation_pool_mine))
        ChipFlow {
            model.looks.forEach { look -> ChoiceChip(look.name, look.style in switching.mine) { set(switching.toggled(look.style)) } }
        }
    }
    HeldSlider(
        label = stringResource(R.string.automation_transition),
        value = switching.seconds,
        range = StyleSwitching.MIN_TRANSITION.value / MILLIS_PER_SECOND..StyleSwitching.MAX_TRANSITION.value / MILLIS_PER_SECOND,
        text = { stringResource(R.string.automation_seconds, it) },
        onSet = { set(switching.transitionOf(it)) },
    )
    QuietText(stringResource(R.string.automation_no_flash))
}

@Composable
private fun CadenceChips(
    cadence: Cadence,
    onChoose: (Cadence) -> Unit,
) {
    ChipFlow {
        CADENCES.forEach { choice -> ChoiceChip(cadenceName(choice), choice == cadence) { onChoose(choice) } }
    }
}

@Composable
private fun cadenceName(cadence: Cadence): String =
    when (cadence) {
        Cadence.BigMoments -> stringResource(R.string.automation_big_moments)
        is Cadence.EveryBars -> stringResource(R.string.automation_every_bars, cadence.bars)
    }

@Composable
private fun poolName(pool: StylePool): String =
    stringResource(
        when (pool) {
            StylePool.MINE -> R.string.automation_pool_mine
            StylePool.PICKS -> R.string.automation_pool_picks
            StylePool.BOTH -> R.string.automation_pool_both
        },
    )

/** A labelled on/off switch. */
@Composable
private fun SwitchRow(
    label: String,
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = onChange)
    }
}
