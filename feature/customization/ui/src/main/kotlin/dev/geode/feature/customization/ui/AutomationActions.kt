package dev.geode.feature.customization.ui

import dev.geode.engine.types.Millis
import dev.geode.engine.types.SceneId
import dev.geode.feature.customization.api.Automations
import dev.geode.feature.customization.api.Autopilot
import dev.geode.feature.customization.api.Cadence
import dev.geode.feature.customization.api.CameraMove
import dev.geode.feature.customization.api.ModulatorParameter
import dev.geode.feature.customization.api.PaletteChanges
import dev.geode.feature.customization.api.Sequenced
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.StyleSwitching
import dev.geode.feature.customization.api.Target
import kotlin.math.roundToLong

// What each control of the Automations section asks for. Every change sends the whole Automations
// once (CustomizationCommand.SetAutomations), so it is one undo step.

/** The cadences the Automations offer. */
internal val CADENCES: List<Cadence> =
    listOf(Cadence.BigMoments, Cadence.EveryBars(4), Cadence.EveryBars(8), Cadence.EveryBars(16))

/** The steps a new sequence starts from: on, off, half, off. */
internal val NEW_SEQUENCE_STEPS: List<Float> = listOf(1f, 0f, 0.5f, 0f)

internal const val MILLIS_PER_SECOND = 1_000f

/** Style switching turned on (from its defaults, or as it was) or off. */
fun Automations.switchingStyle(on: Boolean): Automations = copy(style = if (on) style ?: StyleSwitching() else null)

/** Palette changes turned on or off. */
fun Automations.changingPalette(on: Boolean): Automations = copy(palette = if (on) palette ?: PaletteChanges() else null)

/** [style] added to or taken off the person's own list. */
fun StyleSwitching.toggled(style: SceneId): StyleSwitching = copy(mine = if (style in mine) mine - style else mine + style)

/** The transition set from the slider's [seconds]. */
fun StyleSwitching.transitionOf(seconds: Float): StyleSwitching = copy(transition = Millis((seconds * MILLIS_PER_SECOND).roundToLong()))

/** The transition in seconds, for the slider. */
val StyleSwitching.seconds: Float
    get() = transition.value / MILLIS_PER_SECOND

/** A Camera move on the beat added for [property], or taken away when it has one. */
fun Automations.toggledCamera(property: SharedProperty): Automations =
    copy(camera = if (camera.any { it.property == property }) camera.filterNot { it.property == property } else camera + CameraMove(property))

/** A sequence added for [target], or taken away when it has one. */
fun Automations.toggledSequence(target: Target): Automations =
    copy(
        sequences =
            if (sequences.any { it.target == target }) {
                sequences.filterNot { it.target == target }
            } else {
                sequences + Sequenced(target, NEW_SEQUENCE_STEPS)
            },
    )

/** The autopilot of Macro [macro] turned on, or off when it has one. */
fun Automations.toggledAutopilot(macro: Int): Automations =
    copy(autopilots = if (autopilots.any { it.macro == macro }) autopilots.filterNot { it.macro == macro } else autopilots + Autopilot(macro))

/**
 * What can be sequenced from this screen: the basic view's effects that do not move overall
 * brightness, and every Modulator's depth.
 */
fun CustomizeModel.sequenceTargets(): List<Target> =
    EFFECT_TOGGLES.filterNot { it.luminous }.map { Target.Shared(it) } +
        modulators.map { Target.ModulatorField(it.modulator.id, ModulatorParameter.DEPTH) }
