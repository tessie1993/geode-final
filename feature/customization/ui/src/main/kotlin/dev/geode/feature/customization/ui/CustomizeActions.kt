package dev.geode.feature.customization.ui

import dev.geode.engine.types.Millis
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.Layer
import dev.geode.feature.customization.api.LayerLoop
import dev.geode.feature.customization.api.LoopPreset
import dev.geode.feature.customization.api.LoopTarget
import dev.geode.feature.customization.api.MacroLink
import dev.geode.feature.customization.api.Modulator
import dev.geode.feature.customization.api.ModulatorId
import dev.geode.feature.customization.api.ModulatorShape
import dev.geode.feature.customization.api.PRESET_VERSION
import dev.geode.feature.customization.api.Preset
import dev.geode.feature.customization.api.PresetPart
import dev.geode.feature.customization.api.Reaction
import dev.geode.feature.customization.api.ReactionId
import dev.geode.feature.customization.api.ReactionSource
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.SoundSignal
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.ValueKind
import dev.geode.feature.customization.api.Wave
import kotlin.random.Random

/** The one callback the Customize panel sends (ADR-0003). */
sealed interface CustomizeAction {
    /** A Customization Command, as it is. */
    data class Send(
        val command: CustomizationCommand,
    ) : CustomizeAction

    /** Randomize the Macros only, within the safe range; the route draws the values. */
    data object SurpriseMe : CustomizeAction

    /** Share the current Customization as a preset file or code, through the share sheet. */
    data object Share : CustomizeAction

    /** The panel was swiped away: the Visual is shown alone. */
    data object Hide : CustomizeAction

    /** Open the Presets browser (#144), beside this Style's preset chips. */
    data object BrowsePresets : CustomizeAction
}

/** Surprise me keeps every Macro within this range, clear of both ends (#12). */
const val SURPRISE_LOW: Float = 0.2f
const val SURPRISE_HIGH: Float = 0.8f

/** The strength a new link starts at: half, so its effect shows without taking over. */
internal const val NEW_LINK_AMOUNT = 0.5f

/** The ticks of the save dialog before the person changes any: everything. */
val DEFAULT_PRESET_PARTS: Set<PresetPart> = PresetPart.entries.toSet()

/** The id a new Reaction or Modulator carries; the store gives it a fresh one. */
private const val NEW_ID = 0

/** The name of the throwaway preset that carries Surprise me's Macros as one undoable step. */
private const val SURPRISE_NAME = "Surprise"

/** The shapes a new Modulator starts from: calm, so it shows without taking over. */
private val NEW_LFO_PERIOD = Millis(4_000)
private val NEW_ENVELOPE_ATTACK = Millis(30)
private val NEW_ENVELOPE_RELEASE = Millis(400)
private const val NEW_ENVELOPE_THRESHOLD = 0.5f
private val NEW_SEQUENCER_STEPS = listOf(1f, 0f, 0.5f, 0f, 1f, 0f, 0.75f, 0.25f)
private val NEW_SEQUENCER_STEP = Millis(250)
private val NEW_DRIFT_PERIOD = Millis(8_000)
private const val NEW_BEATS_BETWEEN = 4

/** The beats a beat Modulator can wait between changes, as its editor offers them. */
val BEATS_BETWEEN_CHOICES: List<Int> = listOf(1, 2, 4, 8, 16)

/** The value each level effect is turned on at from the basic view's toggles. */
private val EFFECT_ON_VALUES =
    mapOf(
        SharedProperty.TRAILS to 0.6f,
        SharedProperty.GLITCH to 0.1f,
        SharedProperty.PARTICLES to 0.5f,
    )

/**
 * Surprise me (#12): every Macro of [customization] at a random value within [SURPRISE_LOW] and
 * [SURPRISE_HIGH], their names and links unchanged. It is sent as a Macros-only preset so that it is
 * one step to undo, not six.
 */
fun surprise(
    customization: Customization,
    random: Random,
): CustomizationCommand.ApplyPreset {
    val macros =
        customization.macros.map { macro ->
            macro.copy(value = SURPRISE_LOW + random.nextFloat() * (SURPRISE_HIGH - SURPRISE_LOW))
        }
    val preset =
        Preset(
            version = PRESET_VERSION,
            name = SURPRISE_NAME,
            style = customization.style,
            parts = setOf(PresetPart.MACROS),
            macros = macros,
        )
    return CustomizationCommand.ApplyPreset(preset)
}

/** What moving the slider of [target] to [value] asks for; null when [target] has no slider. */
fun sliderCommand(
    target: Target,
    value: Float,
): CustomizationCommand? =
    when (target) {
        is Target.Shared -> CustomizationCommand.SetShared(target.property, value)
        is Target.Extra -> CustomizationCommand.SetExtra(target.kind, value)
        is Target.MacroSlot -> CustomizationCommand.SetMacro(target.index, value)
        is Target.ModulatorField -> null
    }

/**
 * Learn mode (#12): while a [learning] source is chosen, the first slider moved is linked to it
 * instead of being set. Otherwise the slider is set as usual.
 */
fun learnOrSet(
    learning: ReactionSource?,
    target: Target,
    value: Float,
): CustomizationCommand? =
    if (learning != null) CustomizationCommand.AddReaction(newReaction(learning, target)) else sliderCommand(target, value)

/**
 * What a slider dragged to [value] previews (#285): the edit letting go would make, shown while the
 * finger moves. Nothing in Learn mode ([learning]), where letting go links the slider instead.
 */
fun previewCommand(
    learning: ReactionSource?,
    target: Target,
    value: Float,
): CustomizationCommand? = if (learning != null) null else sliderCommand(target, value)?.let(CustomizationCommand::Preview)

/** A signal's trim set to [gain] (#301), its floor kept. */
fun trimCommand(
    row: TrimRow,
    gain: Float,
): CustomizationCommand.TrimSignal = CustomizationCommand.TrimSignal(row.signal, row.trim.copy(gain = gain))

/** The toggle flipped: an effect turned off goes back to its minimum; one turned on to its on value. */
fun EffectToggle.toggled(): CustomizationCommand.SetShared {
    val onValue = if (property.kind == ValueKind.SWITCH) property.max else EFFECT_ON_VALUES[property] ?: property.max
    return CustomizationCommand.SetShared(property, if (on) property.min else onValue)
}

/** A new link from [source] to [target], at [NEW_LINK_AMOUNT] and otherwise the Reaction's defaults. */
fun newReaction(
    source: ReactionSource,
    target: Target,
): Reaction = Reaction(ReactionId(NEW_ID), source, target, amount = NEW_LINK_AMOUNT)

/** A Reaction from the editor: added when it is [isNew], otherwise it replaces the one with its id. */
fun saveReaction(
    reaction: Reaction,
    isNew: Boolean,
): CustomizationCommand = if (isNew) CustomizationCommand.AddReaction(reaction) else CustomizationCommand.UpdateReaction(reaction)

/** A Macro's new link to [target], across the whole range until the person narrows it. */
fun linkMacro(
    index: Int,
    target: Target,
): CustomizationCommand.LinkMacro = CustomizationCommand.LinkMacro(index, MacroLink(target, 0f, 1f))

/** The save dialog's answer, or null while it has no name or no part is ticked. */
fun savePreset(
    name: String,
    parts: Set<PresetPart>,
): CustomizationCommand.SavePreset? = if (name.isBlank() || parts.isEmpty()) null else CustomizationCommand.SavePreset(name.trim(), parts)

/** Layer 2's loop switched on, as the first ready-made loop, or off. */
fun loopToggled(layer: Layer): CustomizationCommand.SetLayerLoop =
    CustomizationCommand.SetLayerLoop(if (layer.loop == null) LoopPreset.BREATHE.loop else null)

/**
 * The loop with [target] added, or taken away when it is already moved. The last target stays: a loop
 * that moves nothing would be no loop, and the switch is the way to stop it.
 */
fun LayerLoop.toggled(target: LoopTarget): CustomizationCommand.SetLayerLoop? =
    when {
        target !in targets -> CustomizationCommand.SetLayerLoop(copy(targets = targets + target))
        targets.size > 1 -> CustomizationCommand.SetLayerLoop(copy(targets = targets - target))
        else -> null
    }

/** [shape], a beat Modulator, changing every [beats] heard beats; any other shape as it is. */
fun ModulatorShape.withBeatsBetween(beats: Int): ModulatorShape =
    when (this) {
        is ModulatorShape.ToggleOnBeat -> copy(everyBeats = beats)
        is ModulatorShape.RandomOnBeat -> copy(everyBeats = beats)
        else -> this
    }

/** How many heard beats a beat Modulator waits between changes; null for the other kinds. */
val ModulatorShape.beatsBetween: Int?
    get() =
        when (this) {
            is ModulatorShape.ToggleOnBeat -> everyBeats
            is ModulatorShape.RandomOnBeat -> everyBeats
            else -> null
        }

/** Play a stopped hand-started Modulator, or stop a playing one. */
fun togglePlay(modulator: Modulator): CustomizationCommand.PlayModulator =
    CustomizationCommand.PlayModulator(modulator.id, !modulator.playing)

/** The kinds of Modulator (#11, and the beat Modulators of #319), as the add menu offers them. */
enum class ModulatorKind {
    LFO,
    ENVELOPE,
    SEQUENCER,
    DRIFT,
    TOGGLE_ON_BEAT,
    RANDOM_ON_BEAT,
}

val ModulatorShape.kind: ModulatorKind
    get() =
        when (this) {
            is ModulatorShape.Lfo -> ModulatorKind.LFO
            is ModulatorShape.HitEnvelope -> ModulatorKind.ENVELOPE
            is ModulatorShape.StepSequencer -> ModulatorKind.SEQUENCER
            is ModulatorShape.RandomDrift -> ModulatorKind.DRIFT
            is ModulatorShape.ToggleOnBeat -> ModulatorKind.TOGGLE_ON_BEAT
            is ModulatorShape.RandomOnBeat -> ModulatorKind.RANDOM_ON_BEAT
        }

/** A new Modulator of [kind], from a calm shape the person then edits. It runs once something listens to it. */
fun newModulator(kind: ModulatorKind): Modulator =
    Modulator(
        id = ModulatorId(NEW_ID),
        shape =
            when (kind) {
                ModulatorKind.LFO -> ModulatorShape.Lfo(Wave.SINE, NEW_LFO_PERIOD)
                ModulatorKind.ENVELOPE ->
                    ModulatorShape.HitEnvelope(SoundSignal.KICK, NEW_ENVELOPE_ATTACK, NEW_ENVELOPE_RELEASE, NEW_ENVELOPE_THRESHOLD)
                ModulatorKind.SEQUENCER -> ModulatorShape.StepSequencer(NEW_SEQUENCER_STEPS, NEW_SEQUENCER_STEP, beatSync = true)
                ModulatorKind.DRIFT -> ModulatorShape.RandomDrift(NEW_DRIFT_PERIOD)
                ModulatorKind.TOGGLE_ON_BEAT -> ModulatorShape.ToggleOnBeat(NEW_BEATS_BETWEEN)
                ModulatorKind.RANDOM_ON_BEAT -> ModulatorShape.RandomOnBeat(NEW_BEATS_BETWEEN)
            },
    )
