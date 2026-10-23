package dev.geode.feature.customization.core

import dev.geode.engine.types.PresetId
import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.CustomizationCommand
import dev.geode.feature.customization.api.CustomizationFact
import dev.geode.feature.customization.api.Preset
import dev.geode.kernel.Effect

/** The name Customization reports its failures under, on the app-wide error channel. */
const val CUSTOMIZATION_FEATURE = "customization"

/** Everything the reducer handles: the commands, and what the handlers report back. */
sealed interface CustomizationEvent {
    data class Command(
        val command: CustomizationCommand,
    ) : CustomizationEvent

    /** The preset asked for with [CustomizationWork.LoadPreset] was read. */
    data class PresetLoaded(
        val preset: Preset,
    ) : CustomizationEvent

    /**
     * The engine heard the moment Style switching waits for ([Automation.due] on its cadence, evaluated
     * per frame). The reducer moves to the next Style; the engine crossfades into it.
     */
    data object StyleDue : CustomizationEvent

    /** A piece of work failed at the handlers' edge; the reducer reports it. */
    data class WorkFailed(
        val failure: WorkFailure,
    ) : CustomizationEvent
}

/** Work the handlers could not carry out, named for the log and the app-wide banner. */
enum class WorkFailure(
    val message: String,
) {
    PRESET_NOT_LOADED("could not load the preset"),
    PRESET_NOT_SAVED("could not save the preset"),
    ENGINE_REFUSED("the engine did not take the Customization"),
    PRESET_NOT_MIRRORED("saved the preset, but could not copy it to the mirror folder"),
    PRESETS_NOT_UPGRADED("could not upgrade the presets saved by an older Geode"),
}

/** The handlers' work, carried out by `:feature:customization:handlers`. */
sealed interface CustomizationWork {
    /** Hand [customization] to the engine as one snapshot (ADR-0002, ADR-0004). Nothing crosses per frame. */
    data class Publish(
        val customization: Customization,
    ) : CustomizationWork

    /** Write [preset] as a versioned preset file. */
    data class SavePreset(
        val preset: Preset,
    ) : CustomizationWork

    /** Read the preset [id]; answered with [CustomizationEvent.PresetLoaded]. */
    data class LoadPreset(
        val id: PresetId,
    ) : CustomizationWork

    /**
     * Convert every preset an older Geode saved (version 1) into the current format, once: each
     * original is backed up before it is replaced, so a preset already converted is left alone.
     */
    data object UpgradePresets : CustomizationWork
}

typealias CustomizationEffect = Effect<CustomizationWork, CustomizationEvent, CustomizationFact>
