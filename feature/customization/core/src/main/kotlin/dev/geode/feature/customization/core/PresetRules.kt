package dev.geode.feature.customization.core

import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.MACRO_COUNT
import dev.geode.feature.customization.api.PRESET_VERSION
import dev.geode.feature.customization.api.Preset
import dev.geode.feature.customization.api.PresetPart

/** Saving a preset with the parts the person ticked, and applying one. */
object PresetRules {
    /** Every part: what an upgraded old preset keeps. */
    val ALL_PARTS: Set<PresetPart> = PresetPart.entries.toSet()

    /** Whether [part] applies on any Style, not only on the one the preset was made on. */
    fun shared(part: PresetPart): Boolean = part.group != null || part == PresetPart.SOUND

    /** [customization] as a preset named [name] that holds only the ticked [parts]. */
    fun capture(
        name: String,
        customization: Customization,
        parts: Set<PresetPart>,
    ): Preset {
        val groups = parts.mapNotNull { it.group }.toSet()
        return Preset(
            version = PRESET_VERSION,
            name = name.trim(),
            style = customization.style,
            parts = parts,
            shared = customization.shared.filterKeys { it.group in groups },
            extras = if (PresetPart.EXTRAS in parts) customization.extras else emptyMap(),
            macros = if (PresetPart.MACROS in parts) customization.macros else emptyList(),
            reactions = if (PresetPart.REACTIONS in parts) customization.reactions else emptyList(),
            modulators = if (PresetPart.MODULATORS in parts) customization.modulators else emptyList(),
            signals = if (PresetPart.SOUND in parts) customization.signals else emptyMap(),
        )
    }

    /**
     * [preset]'s kept parts applied to [customization] (#11). A kept [PresetPart.STYLE] switches to the
     * preset's Style first, if this version knows it. On the preset's own Style every kept part
     * applies; on another Style only the shared parts do. Links left pointing at nothing are dropped.
     */
    fun apply(
        customization: Customization,
        preset: Preset,
        styles: StyleCatalogue,
    ): Customization {
        val target = styles[preset.style]
        val base =
            if (PresetPart.STYLE in preset.parts && target != null && preset.style != customization.style) {
                Edits.switchStyle(customization, styles.manifestOf(customization.style), target)
            } else {
                customization
            }
        val groups = preset.parts.mapNotNull { it.group }.toSet()
        val kept = { part: PresetPart -> part in preset.parts && (shared(part) || preset.style == base.style) }
        val keptShared = preset.shared.filterKeys { it.group in groups }.mapValues { (property, value) -> Rules.shared(property, value) }
        val next =
            base.copy(
                shared = base.shared + keptShared,
                signals = if (kept(PresetPart.SOUND)) base.signals + preset.signals.mapValues { Rules.trim(it.value) } else base.signals,
                extras =
                    if (kept(PresetPart.EXTRAS)) {
                        base.extras + preset.extras.filterKeys { it in base.extras }.mapValues { Rules.unit(it.value) }
                    } else {
                        base.extras
                    },
                macros = if (kept(PresetPart.MACROS) && preset.macros.size == MACRO_COUNT) preset.macros else base.macros,
                modulators = if (kept(PresetPart.MODULATORS)) preset.modulators.map(Rules::modulator) else base.modulators,
                reactions = if (kept(PresetPart.REACTIONS)) preset.reactions.map(Rules::reaction) else base.reactions,
            )
        return Rules.prune(next)
    }
}
