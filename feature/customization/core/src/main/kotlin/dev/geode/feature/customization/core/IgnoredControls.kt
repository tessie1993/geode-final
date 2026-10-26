package dev.geode.feature.customization.core

import dev.geode.feature.customization.api.Customization
import dev.geode.feature.customization.api.SharedProperty
import dev.geode.feature.customization.api.Target

/**
 * The controls the current Style leaves alone (#365): its family's renderer never reads them, so
 * Customize greys them out rather than offer a control that does nothing (MilkDrop paces itself and
 * takes raw sound, so Speed and Audio drive cannot reach it). Which Shared core properties a family
 * ignores is data, the family's `ignores` in the Style packs' families.json (docs/style-packs.md);
 * nothing here names a Style or a family.
 */
object IgnoredControls {
    /** The Shared core properties [names] declares, by property name; a name this version does not know is left out. */
    fun properties(names: Collection<String>): Set<SharedProperty> =
        names.mapNotNullTo(LinkedHashSet()) { name -> SharedProperty.entries.firstOrNull { it.name == name } }

    /**
     * What Customize greys out on [customization] when its Style ignores [ignored]: each ignored
     * property, and each Macro whose every link moves an ignored property, since turning it changes
     * nothing. A Macro without links is left alone: it moves nothing yet, ignored or not.
     */
    fun targets(
        customization: Customization,
        ignored: Set<SharedProperty>,
    ): Set<Target> {
        val shared: Set<Target> = ignored.mapTo(LinkedHashSet()) { Target.Shared(it) }
        val macros =
            customization.macros.indices
                .filter { index ->
                    val links = customization.macros[index].links
                    links.isNotEmpty() && links.all { it.target in shared }
                }.map { Target.MacroSlot(it) }
        return shared + macros
    }
}
