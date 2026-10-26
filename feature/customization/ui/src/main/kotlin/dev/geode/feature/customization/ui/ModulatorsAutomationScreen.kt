package dev.geode.feature.customization.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import dev.geode.design.glass.GlassBackdrop
import dev.geode.design.glass.GlassKind
import dev.geode.design.glass.GlassSpacing
import dev.geode.design.glass.LocalGlassStyle
import dev.geode.design.glass.glassSurface
import dev.geode.feature.customization.api.Automations
import dev.geode.feature.customization.api.BeatSpan
import dev.geode.feature.customization.api.Cadence
import dev.geode.feature.customization.api.CameraMoveKind
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.PaletteChanges
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.StartMode
import dev.geode.feature.customization.api.StepLength
import dev.geode.feature.customization.api.StylePool
import dev.geode.feature.customization.api.StyleSwitching
import dev.geode.feature.customization.api.Trigger
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
    // Over the live Visual: matte glass until the engine frosts it (#349).
    val shape = MaterialTheme.shapes.extraLarge
    Surface(
        shape = shape,
        color = Color.Transparent,
        contentColor = LocalGlassStyle.current.colors.textPrimary,
        modifier = modifier.fillMaxSize().glassSurface(GlassKind.SHEET, shape, GlassBackdrop.Visual),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GlassSpacing.Large, vertical = GlassSpacing.Medium),
        ) {
            Text(stringResource(R.string.modulators_automation_title), style = MaterialTheme.typography.headlineMedium)
            SectionTitle(stringResource(R.string.customize_section_modulators))
            ModulatorsSection(model, send)
            SectionTitle(stringResource(R.string.automation_section))
            AutomationsSection(model, send)
        }
    }
}

/** Modulators: add one of the kinds; each shows its picture, its start setting, rate and depth. */
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
                leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.customize_add_modulator)) },
                shape = MaterialTheme.shapes.small,
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
                        Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.customize_stop))
                    } else {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.customize_play))
                    }
                }
            }
            IconButton(onClick = { send(CustomizationCommand.RemoveModulator(modulator.id)) }) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.customize_remove_modulator))
            }
        }
        if (!row.linked) QuietText(stringResource(R.string.customize_modulator_idle))
        ModulatorPicture(modulator.shape, Modifier.padding(vertical = GlassSpacing.Small))
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
            is ModulatorShape.ToggleOnBeat, is ModulatorShape.RandomOnBeat -> {
                SectionTitle(stringResource(R.string.customize_beats_between))
                ChipFlow {
                    BEATS_BETWEEN_CHOICES.forEach { beats ->
                        ChoiceChip(beats.toString(), shape.beatsBetween == beats) {
                            update(modulator.copy(shape = shape.withBeatsBetween(beats)))
                        }
                    }
                }
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
            help = stringResource(R.string.customize_help_rate),
        )
        HeldSlider(
            label = stringResource(R.string.customize_depth),
            value = modulator.depth,
            range = 0f..1f,
            text = { stringResource(R.string.customize_percent, percentOf(it)) },
            onSet = { update(modulator.copy(depth = it)) },
            help = stringResource(R.string.customize_help_depth),
        )
    }
}

/**
 * The Automations: Style switching, palette changes, Camera moves on the beat and sequence lanes. Each
 * is off until turned on; every change is one undo step. There is no autopilot: a Macro drifts only
 * when a Modulator is linked to it.
 */
@Composable
private fun AutomationsSection(
    model: CustomizeModel,
    send: (CustomizationCommand) -> Unit,
) {
    val automations = model.automations
    val set = { next: Automations -> send(CustomizationCommand.SetAutomations(next)) }
    QuietText(stringResource(R.string.automation_live_only))
    InsetCard {
        SwitchRow(stringResource(R.string.automation_style), automations.style != null) { set(automations.switchingStyle(it)) }
        automations.style?.let { switching ->
            StyleSwitchingControls(model, switching) { set(automations.copy(style = it)) }
            SwitchRow(stringResource(R.string.automation_hold), model.styleHeld) { send(CustomizationCommand.HoldStyle(it)) }
        }
        StylesSeen(model, send)
    }
    InsetCard {
        SwitchRow(stringResource(R.string.automation_palette), automations.palette != null) { set(automations.changingPalette(it)) }
        automations.palette?.let { palette -> PaletteControls(palette) { set(automations.copy(palette = it)) } }
    }
    InsetCard { CameraMoves(automations, set) }
    InsetCard { Lanes(model, set) }
    QuietText(stringResource(R.string.automation_macro_drift))
}

@Composable
private fun StyleSwitchingControls(
    model: CustomizeModel,
    switching: StyleSwitching,
    set: (StyleSwitching) -> Unit,
) {
    CadenceControls(switching.cadence) { set(switching.copy(cadence = it)) }
    ChipFlow {
        POOL_CHOICES.forEach { pool -> ChoiceChip(poolName(pool), switching.pool == pool) { set(switching.copy(pool = pool)) } }
        // A folder's Styles, taken as they are now (#308).
        model.styleFolders.forEach { folder ->
            ChoiceChip(stringResource(R.string.automation_pool_folder, folder.name), switching.within(folder)) {
                set(switching.withinFolder(folder))
            }
        }
    }
    // The two Styles a pair goes back and forth between (#384).
    if (switching.pool == StylePool.PAIR) {
        QuietText(stringResource(R.string.automation_pair_hint))
        ChipFlow {
            model.looks.forEach { look -> ChoiceChip(look.name, look.style in switching.pair) { set(switching.pairToggled(look.style)) } }
        }
    }
    QuietText(stringResource(R.string.automation_marks))
    HeldSlider(
        label = stringResource(R.string.automation_transition),
        value = switching.seconds,
        range = StyleSwitching.MIN_TRANSITION.value / MILLIS_PER_SECOND..StyleSwitching.MAX_TRANSITION.value / MILLIS_PER_SECOND,
        text = { stringResource(R.string.automation_seconds, it) },
        onSet = { set(switching.transitionOf(it)) },
    )
    QuietText(stringResource(R.string.automation_no_flash))
}

/** Back and forward through the Styles seen this session (#282); not undo steps. */
@Composable
private fun StylesSeen(
    model: CustomizeModel,
    send: (CustomizationCommand) -> Unit,
) {
    if (!model.canGoBack && !model.canGoForward) return
    ChipFlow {
        AssistChip(
            onClick = { send(CustomizationCommand.StyleBack) },
            label = { Text(stringResource(R.string.automation_style_back)) },
            enabled = model.canGoBack,
            shape = MaterialTheme.shapes.small,
        )
        AssistChip(
            onClick = { send(CustomizationCommand.StyleForward) },
            label = { Text(stringResource(R.string.automation_style_forward)) },
            enabled = model.canGoForward,
            shape = MaterialTheme.shapes.small,
        )
    }
}

@Composable
private fun PaletteControls(
    palette: PaletteChanges,
    set: (PaletteChanges) -> Unit,
) {
    CadenceControls(palette.cadence) { set(palette.copy(cadence = it)) }
    HeldSlider(
        label = stringResource(R.string.automation_palette_fade),
        value = palette.seconds,
        range = PaletteChanges.MIN_FADE.value / MILLIS_PER_SECOND..PaletteChanges.MAX_FADE.value / MILLIS_PER_SECOND,
        text = { stringResource(R.string.automation_seconds, it) },
        onSet = { set(palette.fadeOf(it)) },
    )
}

/** The trigger, its threshold (on big moments) and the minimum spacing between moves. */
@Composable
private fun CadenceControls(
    cadence: Cadence,
    set: (Cadence) -> Unit,
) {
    ChipFlow {
        TRIGGERS.forEach { choice ->
            ChoiceChip(triggerName(choice), cadence.trigger.sameChoice(choice)) { set(cadence.triggeredBy(choice)) }
        }
    }
    val trigger = cadence.trigger
    if (trigger is Trigger.BigMoments) {
        HeldSlider(
            label = stringResource(R.string.automation_threshold),
            value = trigger.threshold,
            range = Trigger.BigMoments.MIN_THRESHOLD..1f,
            text = { stringResource(R.string.customize_percent, percentOf(it)) },
            onSet = { set(cadence.thresholdOf(it)) },
        )
    }
    HeldSlider(
        label = stringResource(R.string.automation_spacing),
        value = cadence.spacingSeconds.coerceAtMost(MAX_SPACING_SECONDS),
        range = 0f..MAX_SPACING_SECONDS,
        text = { stringResource(R.string.automation_seconds, it) },
        onSet = { set(cadence.spacingOf(it)) },
    )
}

/** The Camera moves on the beat: which moves, every 1, 2 or 4 beats, and how strong. */
@Composable
private fun CameraMoves(
    automations: Automations,
    set: (Automations) -> Unit,
) {
    Text(stringResource(R.string.automation_camera), style = MaterialTheme.typography.bodyMedium)
    ChipFlow {
        CameraMoveKind.entries.forEach { kind ->
            ChoiceChip(cameraMoveName(kind), automations.camera.any { it.kind == kind }) { set(automations.toggledCamera(kind)) }
        }
    }
    automations.camera.forEach { move ->
        SectionTitle(cameraMoveName(move.kind))
        ChipFlow {
            BeatSpan.entries.forEach { span ->
                ChoiceChip(spanName(span), move.every == span) {
                    set(automations.withCamera(move.every(span)))
                }
            }
        }
        HeldSlider(
            label = stringResource(R.string.automation_strength),
            value = move.strength,
            range = 0f..1f,
            text = { stringResource(R.string.customize_percent, percentOf(it)) },
            onSet = { set(automations.withCamera(move.copy(strength = it))) },
        )
    }
}

/** The sequence lanes: each steps several targets together, by beat or by bar. */
@Composable
private fun Lanes(
    model: CustomizeModel,
    set: (Automations) -> Unit,
) {
    val automations = model.automations
    val targets = model.sequenceTargets()
    val free = automations.freeTargets(targets)
    Text(stringResource(R.string.automation_sequences), style = MaterialTheme.typography.bodyMedium)
    QuietText(stringResource(R.string.automation_sequences_hint))
    automations.lanes.forEachIndexed { index, lane ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(stringResource(R.string.automation_lane, index + 1), Modifier.weight(1f))
            IconButton(onClick = { set(automations.withoutLane(index)) }) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.automation_remove_lane))
            }
        }
        ChipFlow {
            StepLength.entries.forEach { step ->
                ChoiceChip(stepName(step), lane.step == step) { set(automations.laneStepping(index, step)) }
            }
        }
        ChipFlow {
            (lane.targets + free).forEach { target ->
                ChoiceChip(targetName(target, model), target in lane.targets) { set(automations.toggledInLane(index, target)) }
            }
        }
    }
    if (free.isNotEmpty() && automations.lanes.size < Automations.MAX_LANES) {
        SectionTitle(stringResource(R.string.automation_new_lane))
        ChipFlow {
            free.forEach { target -> ChoiceChip(targetName(target, model), false) { set(automations.newLane(target)) } }
        }
    }
}

@Composable
private fun triggerName(trigger: Trigger): String =
    when (trigger) {
        is Trigger.BigMoments -> stringResource(R.string.automation_big_moments)
        is Trigger.EveryBars -> stringResource(R.string.automation_every_bars, trigger.bars)
    }

@Composable
private fun spanName(span: BeatSpan): String =
    stringResource(
        when (span) {
            BeatSpan.ONE -> R.string.automation_span_one
            BeatSpan.TWO -> R.string.automation_span_two
            BeatSpan.FOUR -> R.string.automation_span_four
        },
    )

@Composable
private fun stepName(step: StepLength): String =
    stringResource(
        when (step) {
            StepLength.BEAT -> R.string.automation_step_beat
            StepLength.BAR -> R.string.automation_step_bar
        },
    )

@Composable
private fun poolName(pool: StylePool): String =
    stringResource(
        when (pool) {
            StylePool.VISUALIZER -> R.string.automation_pool_visualizer
            StylePool.PICKS -> R.string.automation_pool_picks
            StylePool.BOTH -> R.string.automation_pool_both
            StylePool.FAVOURITES -> R.string.automation_pool_favourites
            StylePool.CHOSEN -> R.string.automation_pool_chosen
            StylePool.PAIR -> R.string.automation_pool_pair
        },
    )

@Composable
private fun cameraMoveName(kind: CameraMoveKind): String =
    stringResource(
        when (kind) {
            CameraMoveKind.PUSH_IN -> R.string.automation_move_push_in
            CameraMoveKind.PULL_OUT -> R.string.automation_move_pull_out
            CameraMoveKind.ZOOM_PUNCH -> R.string.automation_move_zoom_punch
            CameraMoveKind.ORBIT -> R.string.automation_move_orbit
            CameraMoveKind.ROLL -> R.string.automation_move_roll
            CameraMoveKind.TRAVEL_CURVE -> R.string.automation_move_travel_curve
            CameraMoveKind.DOLLY -> R.string.automation_move_dolly
            CameraMoveKind.TILT -> R.string.automation_move_tilt
            CameraMoveKind.PAN -> R.string.automation_move_pan
            CameraMoveKind.SWAY -> R.string.automation_move_sway
            CameraMoveKind.SHAKE -> R.string.automation_move_shake
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
