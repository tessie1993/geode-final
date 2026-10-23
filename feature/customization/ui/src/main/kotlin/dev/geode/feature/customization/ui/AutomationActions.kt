package dev.geode.feature.customization.ui

import dev.geode.engine.types.Millis
import dev.geode.feature.customization.api.Automations
import dev.geode.feature.customization.api.BeatSpan
import dev.geode.feature.customization.api.Cadence
import dev.geode.feature.customization.api.CameraMove
import dev.geode.feature.customization.api.CameraMoveKind
import dev.geode.feature.customization.api.Lane
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.PaletteChanges
import dev.geode.feature.customization.api.StepLength
import dev.geode.feature.customization.api.StyleSwitching
import dev.geode.feature.customization.api.Target
import dev.geode.feature.customization.api.Trigger
import kotlin.math.roundToLong

// What each control of the Automations section asks for. Every change sends the whole Automations
// once (CustomizationCommand.SetAutomations), so it is one undo step.

/** The triggers the Automations offer: big moments, or every 4, 8 or 16 bars. */
internal val TRIGGERS: List<Trigger> =
    listOf(Trigger.BigMoments(), Trigger.EveryBars(4), Trigger.EveryBars(8), Trigger.EveryBars(16))

/** The longest spacing the slider offers, in seconds. */
internal const val MAX_SPACING_SECONDS = 60f

/** The steps a new lane starts from, for each of its targets: on, off, half, off. */
internal val NEW_SEQUENCE_STEPS: List<Float> = listOf(1f, 0f, 0.5f, 0f)

internal const val MILLIS_PER_SECOND = 1_000f

/** Style switching turned on (from its defaults, or as it was) or off. */
fun Automations.switchingStyle(on: Boolean): Automations = copy(style = if (on) style ?: StyleSwitching() else null)

/** Palette changes turned on or off. */
fun Automations.changingPalette(on: Boolean): Automations = copy(palette = if (on) palette ?: PaletteChanges() else null)

/** Whether [choice] is the same choice of trigger as this one; a big moment at any threshold is one choice. */
fun Trigger.sameChoice(choice: Trigger): Boolean =
    when (this) {
        is Trigger.BigMoments -> choice is Trigger.BigMoments
        is Trigger.EveryBars -> this == choice
    }

/** The cadence on [choice], keeping its threshold when it was already on big moments. */
fun Cadence.triggeredBy(choice: Trigger): Cadence = if (trigger.sameChoice(choice)) this else copy(trigger = choice)

/** The big moments' threshold set to [threshold]; nothing changes on a bar-timed trigger. */
fun Cadence.thresholdOf(threshold: Float): Cadence =
    if (trigger is Trigger.BigMoments) copy(trigger = Trigger.BigMoments(threshold)) else this

/** The spacing set from the slider's [seconds]. */
fun Cadence.spacingOf(seconds: Float): Cadence = copy(spacing = millisOf(seconds))

/** The spacing in seconds, for the slider. */
val Cadence.spacingSeconds: Float
    get() = spacing.value / MILLIS_PER_SECOND

/** The transition set from the slider's [seconds]. */
fun StyleSwitching.transitionOf(seconds: Float): StyleSwitching = copy(transition = millisOf(seconds))

/** The transition in seconds, for the slider. */
val StyleSwitching.seconds: Float
    get() = transition.value / MILLIS_PER_SECOND

/** The palette fade set from the slider's [seconds]. */
fun PaletteChanges.fadeOf(seconds: Float): PaletteChanges = copy(fade = millisOf(seconds))

/** The palette fade in seconds, for the slider. */
val PaletteChanges.seconds: Float
    get() = fade.value / MILLIS_PER_SECOND

/** The Camera move [kind] added from its defaults, or taken away when it is on. */
fun Automations.toggledCamera(kind: CameraMoveKind): Automations =
    copy(camera = if (camera.any { it.kind == kind }) camera.filterNot { it.kind == kind } else camera + CameraMove(kind))

/** [move] in place of the Camera move of its kind. */
fun Automations.withCamera(move: CameraMove): Automations = copy(camera = camera.map { if (it.kind == move.kind) move else it })

/** [move] coming round every [span]. */
fun CameraMove.every(span: BeatSpan): CameraMove = copy(every = span)

/** A new lane on [target], stepping through on, off, half, off. */
fun Automations.newLane(target: Target): Automations = copy(lanes = lanes + Lane(listOf(target), NEW_SEQUENCE_STEPS.map { listOf(it) }))

/**
 * [target] added to lane [index], with the new lane's steps, or taken out of it with its values when
 * it is there. A lane left with no target goes.
 */
fun Automations.toggledInLane(
    index: Int,
    target: Target,
): Automations {
    val lane = lanes.getOrNull(index) ?: return this
    val at = lane.targets.indexOf(target)
    val changed =
        if (at >= 0) {
            Lane(lane.targets - target, lane.steps.map { values -> values.filterIndexed { i, _ -> i != at } }, lane.step)
        } else {
            Lane(
                lane.targets + target,
                lane.steps.mapIndexed {
                    step,
                    values,
                    ->
                    values + NEW_SEQUENCE_STEPS[step % NEW_SEQUENCE_STEPS.size]
                },
                lane.step,
            )
        }
    return copy(lanes = if (changed.targets.isEmpty()) lanes - lane else lanes.map { if (it === lane) changed else it })
}

/** Lane [index] stepped by [step]. */
fun Automations.laneStepping(
    index: Int,
    step: StepLength,
): Automations = copy(lanes = lanes.mapIndexed { i, lane -> if (i == index) lane.copy(step = step) else lane })

/** Lane [index] taken away. */
fun Automations.withoutLane(index: Int): Automations = copy(lanes = lanes.filterIndexed { i, _ -> i != index })

/** The targets no lane uses yet. */
fun Automations.freeTargets(targets: List<Target>): List<Target> = targets.filterNot { target -> lanes.any { target in it.targets } }

/**
 * What can be sequenced from this screen: the basic view's effects that do not move overall
 * brightness, and every Modulator's depth.
 */
fun CustomizeModel.sequenceTargets(): List<Target> =
    EFFECT_TOGGLES.filterNot { it.luminous }.map { Target.Shared(it) } +
        modulators.map { Target.ModulatorField(it.modulator.id, ModulatorParameter.DEPTH) }

private fun millisOf(seconds: Float): Millis = Millis((seconds * MILLIS_PER_SECOND).roundToLong())
